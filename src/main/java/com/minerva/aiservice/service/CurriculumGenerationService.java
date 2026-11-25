package com.minerva.aiservice.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.Normalizer;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.minerva.aiservice.dto.CurriculumGenerationRequest;
import com.minerva.aiservice.dto.CurriculumGenerationResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class CurriculumGenerationService {

    private static final Logger LOGGER =
            Logger.getLogger(CurriculumGenerationService.class.getName());

    /**
     * Fix 3: presupuestos de contexto. El prompt completo (system + user)
     * se mantiene acotado para no desbordar num_ctx del modelo, lo que
     * causaba truncamientos en la entrada y salida incoherente.
     */
    private static final int MAX_NODE_INDEX_CHARS    = 12_000;
    private static final int MAX_SOURCE_TEXT_CHARS   = 24_000;
    private static final int MIN_SOURCE_BUDGET_CHARS = 3_000;

    private final DocumentTextExtractor documentTextExtractor;
    private final AiModelClient aiModelClient;
    private final ObjectMapper objectMapper;

    public CurriculumGenerationResponse generate(
            CurriculumGenerationRequest request,
            MultipartFile[] files) {
        return generate(request, files, progress -> {});
    }

    public CurriculumGenerationResponse generate(
            CurriculumGenerationRequest request,
            MultipartFile[] files,
            Consumer<String> progress) {

        if (request == null || request.arbol() == null
                || !request.arbol().isObject()) {
            throw new InvalidCurriculumTreeException(
                    "El arbol curricular temporal no es valido.");
        }

        List<String> arbolErrors =
                CurriculumGenerationSchema.validateTree(request.arbol());
        if (!arbolErrors.isEmpty()) {
            throw new InvalidCurriculumTreeException(
                    "El arbol curricular temporal no cumple el contrato.",
                    arbolErrors);
        }

        Set<String> processedKeys = new LinkedHashSet<>();
        for (CurriculumGenerationRequest.FuenteProcesada fuente :
                request.fuentesProcesadas()) {
            processedKeys.add(sourceKey(fuente.documentoId(), fuente.checksumSha256()));
        }

        List<CurriculumGenerationResponse.FuenteResultado> sourceResults =
                new ArrayList<>();
        List<CurriculumGenerationResponse.FuenteProcesada> newlyProcessed =
                new ArrayList<>();
        List<String> sourceTexts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<MultipartFile> unusedFiles = files == null
                ? new ArrayList<>()
                : new ArrayList<>(List.of(files));

        for (CurriculumGenerationRequest.FuenteNueva fuente : request.fuentes()) {
            String key = sourceKey(fuente.documentoId(), fuente.checksumSha256());
            if (processedKeys.contains(key)) {
                sourceResults.add(new CurriculumGenerationResponse.FuenteResultado(
                        fuente.documentoId(),
                        fuente.checksumSha256(),
                        "ya_procesada",
                        0,
                        0,
                        "Esta fuente ya fue procesada. Vuelvela a subir si deseas repetir el proceso."));
                warnings.add("La fuente " + fuente.nombre()
                        + " ya fue procesada y no se genero contenido nuevamente.");
                continue;
            }

            MultipartFile file = findSourceFile(fuente, unusedFiles);
            if (file == null) {
                sourceResults.add(new CurriculumGenerationResponse.FuenteResultado(
                        fuente.documentoId(),
                        fuente.checksumSha256(),
                        "fallida",
                        0,
                        0,
                        "No se encontro el archivo asociado a la fuente."));
                continue;
            }
            unusedFiles.remove(file);

            try {
                progress.accept("Extrayendo texto de " + fuente.nombre());
                String text = documentTextExtractor.extract(file);
                if (text == null || text.isBlank()) {
                    sourceResults.add(failedSource(fuente,
                            "La fuente no contiene texto legible."));
                    continue;
                }
                progress.accept("Reconociendo encabezados y fragmentos relevantes");
                String relevantText =
                        CurriculumSourceIndex.selectRelevantFragments(
                                text,
                                request.arbol());
                if (relevantText.isBlank()) {
                    sourceResults.add(failedSource(fuente,
                            "La fuente no contiene texto curricular relevante."));
                    continue;
                }
                sourceTexts.add(
                        "Fuente: " + fuente.nombre() + "\n"
                                + "documentoId: " + fuente.documentoId() + "\n"
                                + "<document>\n" + relevantText + "\n</document>");
                LOGGER.log(Level.INFO,
                        "FUENTE_PROCESADA: documentoId={0}, textoExtraidoChars={1}, textoSeleccionadoChars={2}",
                        new Object[]{fuente.documentoId(), text.length(), relevantText.length()});
                newlyProcessed.add(new CurriculumGenerationResponse.FuenteProcesada(
                        fuente.documentoId(),
                        fuente.checksumSha256()));
                processedKeys.add(key);
                sourceResults.add(new CurriculumGenerationResponse.FuenteResultado(
                        fuente.documentoId(),
                        fuente.checksumSha256(),
                        "pendiente",
                        0,
                        0,
                        null));
            } catch (RuntimeException exception) {
                sourceResults.add(failedSource(fuente,
                        "La fuente no pudo analizarse."));
                LOGGER.log(Level.WARNING,
                        "FUENTE_FALLIDA: documentoId={0}, tipoError={1}, mensaje={2}",
                        new Object[]{fuente.documentoId(),
                                exception.getClass().getSimpleName(),
                                exception.getMessage()});
            }
        }

        if (sourceTexts.isEmpty()) {
            return new CurriculumGenerationResponse(
                    request.grupoId(),
                    false,
                    request.arbol(),
                    sourceResults,
                    mergeProcessed(request.fuentesProcesadas(), newlyProcessed),
                    warnings,
                    sourceResults.isEmpty()
                            ? List.of(new CurriculumGenerationResponse.ErrorDetalle(
                                    "SIN_FUENTES_NUEVAS",
                                    "No hay fuentes nuevas que procesar.",
                                    true))
                            : List.of(new CurriculumGenerationResponse.ErrorDetalle(
                                    "FUENTE_SIN_CONTENIDO",
                                    "No se pudo obtener contenido utilizable de las fuentes nuevas.",
                                    true)));
        }

        String system = threeLayerSystemPrompt();
        String user = threeLayerContextPrompt(request, sourceTexts);
        progress.accept("Consultando el modelo y preparando el arbol actualizado");

        // Fix 3: si Ollama trunca la respuesta (num_predict), reintentamos una
        // vez con menos fuentes para que el modelo tenga presupuesto suficiente.
        String raw;
        try {
            raw = aiModelClient.generateStructured(
                    system,
                    user,
                    CurriculumGenerationSchema.schema());
        } catch (AiModelClient.ModelOutputTruncatedException truncated) {
            LOGGER.log(Level.WARNING,
                    "REINTENTO_TRUNCAMIENTO: respuestaParcialChars={0}, fuentes={1}",
                    new Object[]{truncated.getPartialContent().length(),
                            sourceTexts.size()});
            List<String> reduced = sourceTexts.size() > 1
                    ? sourceTexts.subList(0, 1)
                    : sourceTexts;
            String reducedUser = threeLayerContextPrompt(request, reduced);
            progress.accept("Reintentando con menos fuentes por limite del modelo");
            raw = aiModelClient.generateStructured(
                    system,
                    reducedUser,
                    CurriculumGenerationSchema.schema());
        }

        JsonNode operations;
        try {
            progress.accept("Validando la respuesta del modelo");
            String normalizedRaw = normalizeModelCharacters(raw);
            operations = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(normalizedRaw);
            normalizeNodeKeys(operations);
        } catch (JacksonException exception) {
            LOGGER.log(Level.WARNING,
                    "RESPUESTA_MODELO: jsonInvalido, respuestaChars={0}, tipoError={1}",
                    new Object[]{raw == null ? 0 : raw.length(),
                            exception.getClass().getSimpleName()});
            throw new AiModelClient.InvalidModelResponseException(
                    "El proveedor devolvió JSON inválido.", exception);
        }

        List<String> generatedErrors =
                CurriculumGenerationSchema.validate(operations);
        if (!generatedErrors.isEmpty()) {
            LOGGER.log(Level.WARNING,
                    "RESPUESTA_MODELO: contratoInvalido, errores={0}",
                    generatedErrors.size());
            throw new InvalidCurriculumOutputException(
                    "El modelo devolvio operaciones curriculares invalidas.",
                    generatedErrors);
        }

        progress.accept("Fusionando cambios conservadores con el arbol actual");
        MergeResult mergeResult = mergeOperations(request.arbol(), operations);
        LOGGER.log(Level.INFO,
                "MERGE_CURRICULO: nodosAgregados={0}, contenidosAgregados={1}",
                new Object[]{
                        mergeResult.nodosAgregados(),
                        mergeResult.contenidosAgregados()});
        if (mergeResult.nodosAgregados() == 0
                && mergeResult.contenidosAgregados() == 0) {
            throw new NoRelevantCurriculumException(
                    "Ollama devolvio un arbol valido, pero el merge no agrego nodos ni contenidos nuevos.");
        }
        for (int i = 0; i < sourceResults.size(); i++) {
            CurriculumGenerationResponse.FuenteResultado result =
                    sourceResults.get(i);
            if ("pendiente".equals(result.estado())) {
                sourceResults.set(i, new CurriculumGenerationResponse.FuenteResultado(
                        result.documentoId(),
                        result.checksumSha256(),
                        "procesada",
                        mergeResult.nodosAgregados(),
                        mergeResult.contenidosAgregados(),
                        null));
            }
        }

        return new CurriculumGenerationResponse(
                request.grupoId(),
                false,
                mergeResult.arbol(),
                sourceResults,
                mergeProcessed(request.fuentesProcesadas(), newlyProcessed),
                warnings,
                List.of());
    }

    private MultipartFile findSourceFile(
            CurriculumGenerationRequest.FuenteNueva source,
            List<MultipartFile> availableFiles) {
        for (MultipartFile file : availableFiles) {
            String filename = file.getOriginalFilename();
            if (filename != null
                    && filename.equalsIgnoreCase(source.nombre())) {
                return file;
            }
        }
        if (availableFiles.size() == 1) {
            return availableFiles.get(0);
        }
        return null;
    }

    private CurriculumGenerationResponse.FuenteResultado failedSource(
            CurriculumGenerationRequest.FuenteNueva source,
            String message) {
        return new CurriculumGenerationResponse.FuenteResultado(
                source.documentoId(),
                source.checksumSha256(),
                "fallida",
                0,
                0,
                message);
    }

    private List<CurriculumGenerationResponse.FuenteProcesada> mergeProcessed(
            List<CurriculumGenerationRequest.FuenteProcesada> existing,
            List<CurriculumGenerationResponse.FuenteProcesada> newlyProcessed) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        List<CurriculumGenerationResponse.FuenteProcesada> result = new ArrayList<>();
        for (CurriculumGenerationRequest.FuenteProcesada source : existing) {
            String key = sourceKey(source.documentoId(), source.checksumSha256());
            if (keys.add(key)) {
                result.add(new CurriculumGenerationResponse.FuenteProcesada(
                        source.documentoId(),
                        source.checksumSha256()));
            }
        }
        for (CurriculumGenerationResponse.FuenteProcesada source : newlyProcessed) {
            String key = sourceKey(source.documentoId(), source.checksumSha256());
            if (keys.add(key)) {
                result.add(source);
            }
        }
        return List.copyOf(result);
    }

    private String threeLayerSystemPrompt() {
        return """
                ## Rol:
                Eres un asistente de construccion curricular.
                Recibiras un indice compacto de nodos existentes y fuentes nuevas.
                Devuelve solo operaciones nuevas. No devuelvas el arbol completo.

               ## Reglas obligatorias:
                - Devuelve una sola lista "contenidos" para textos asociados a conceptos existentes.
                - Devuelve una sola lista "nodos" para unidades, temas o conceptos nuevos.
                - Cada contenido debe usar "conceptoId" y "descripcion".
               - Cada nodo nuevo debe usar "tempId", "tipo", "nombre" y "parentId";
                 "contenido" es opcional.
               - Asigna a cada nodo nuevo un "tempId" unico (por ejemplo "n1", "n2").
                 Es una referencia temporal para enlazar nodos dentro de esta respuesta y no
                 sera el id persistido.
               - "parentId" debe referirse exactamente al id de un nodo existente o al "tempId"
                 de otro nodo nuevo de esta misma respuesta.
                - unidad se agrega bajo la materia; tema bajo una unidad; concepto bajo un tema.
                - Trata cada unidad como una rama independiente. Identifica la unidad de cada
                  tema y concepto por la ruta del indice y agrega cada nodo bajo el parentId de
                 su rama; nunca agrupes temas de unidades distintas bajo una sola unidad.
               - Puedes crear en una sola respuesta unidades, sus temas y los conceptos de cada
                 tema. Enlaza cada nivel mediante tempId y parentId.
               - Si agregas una unidad o un tema nuevo, incluye tambien los temas o conceptos
                 respaldados por la fuente; no dejes ramas nuevas vacias cuando la fuente aporta
                 contenido para completarlas.
               - No uses "id" para enlazar nodos nuevos.
                - No agregues conceptos bajo otros conceptos.
                - No repitas conceptos, temas o unidades existentes por nombre.
                - Usa comillas dobles ASCII.
                - No devuelvas claves alternativas ni texto fuera del JSON.
                - No devuelvas texto antes o despues del objeto JSON.
                - No inventes informacion no presente en las fuentes.
                - Agrega solo informacion curricular relevante.
                - Recorre todos los conceptos existentes del indice.
                - Agrega como maximo 1 descripcion por concepto existente.
                - Si un concepto tiene varias frases relevantes, elige solo la mas
                  breve y representativa.
                - Omite un concepto si no tiene informacion verificable en la fuente.
                - Limita la respuesta a cambios verificables presentes en la fuente.
                - Devuelve exclusivamente JSON valido conforme al contrato.
                """;
    }

    /**
     * Fix 3: el indice de nodos se acota a MAX_NODE_INDEX_CHARS para no
     * desbordar num_ctx con arboles grandes. Si se trunca, se indica al
     * modelo con un marcador explicito.
     */
    private String buildNodeIndex(JsonNode root) {
        StringBuilder index = new StringBuilder();
        appendNodeIndex(root, null, "", index, MAX_NODE_INDEX_CHARS);
        if (index.length() >= MAX_NODE_INDEX_CHARS) {
            index.append("- ... (indice truncado por limite de contexto)\n");
        }
        return index.toString();
    }

    private void appendNodeIndex(
            JsonNode node,
            String parentId,
            String parentPath,
            StringBuilder index,
            int maxChars) {
        if (node == null || !node.isObject() || index.length() >= maxChars) {
            return;
        }
        String id = text(node, "id");
        String type = text(node, "tipo");
        String name = text(node, "nombre");
        String path = parentPath.isBlank()
                ? name
                : parentPath + " > " + name;
        if (!id.isBlank() && !type.isBlank() && !name.isBlank()) {
            index.append("- id=").append(id)
                    .append(", tipo=").append(type)
                    .append(", nombre=").append(name)
                    .append(", ruta=").append(path);
            if (parentId != null) {
                index.append(", parentId=").append(parentId);
            }
            index.append('\n');
        }
        JsonNode children = node.get("hijos");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                appendNodeIndex(child, id, path, index, maxChars);
                if (index.length() >= maxChars) {
                    return;
                }
            }
        }
    }

    /**
     * Fix 3: reparte MAX_SOURCE_TEXT_CHARS entre las fuentes nuevas. Si hay
     * muchas fuentes, cada una aporta un fragmento acotado; si hay pocas,
     * cada una recibe un presupuesto amplio. Nunca supera el total.
     */
    private List<String> budgetSourceTexts(List<String> sourceTexts) {
        if (sourceTexts == null || sourceTexts.isEmpty()) {
            return List.of();
        }
        int perSource = Math.max(MIN_SOURCE_BUDGET_CHARS,
                MAX_SOURCE_TEXT_CHARS / sourceTexts.size());
        int remaining = MAX_SOURCE_TEXT_CHARS;
        List<String> result = new ArrayList<>(sourceTexts.size());
        for (String text : sourceTexts) {
            int budget = Math.min(perSource, remaining);
            if (budget <= 0) {
                break;
            }
            if (text.length() <= budget) {
                result.add(text);
                remaining -= text.length();
            } else {
                int cut = Math.max(0, budget - 40);
                result.add(text.substring(0, cut) + "\n... (fragmento truncado)");
                remaining -= budget;
            }
        }
        return result;
    }

    private String threeLayerContextPrompt(
            CurriculumGenerationRequest request,
            List<String> sourceTexts) {
        return """
                Contexto:
                - grupoId: """ + request.grupoId() + """
                - materiaNombre: """ + request.materiaNombre() + """
                - modoFusion: conservadora

                Indice de nodos existentes:
                Cada nodo incluye su ruta curricular completa. Usa la unidad de esa ruta para
                decidir a que rama pertenece cada cambio y conserva la jerarquia unidad -> tema
                -> concepto.
                """ + buildNodeIndex(request.arbol()) + """

                Fuentes nuevas:
                """ + String.join("\n\n", budgetSourceTexts(sourceTexts)) + """

                Tarea:
                Analiza solo las fuentes nuevas.
                Reparte los temas y conceptos entre las unidades que correspondan por evidencia
                en la fuente; no combines varias unidades ni sus temas en una sola rama. Para
                cada nodo nuevo, usa un tempId unico. parentId puede apuntar al id exacto de un
                nodo del indice o al tempId de otro nodo de esta respuesta. Incluye la rama
                completa (unidad, temas y conceptos) cuando la fuente permita identificarla.
                Lista solo contenido nuevo para conceptos existentes usando conceptoId.
                Emite como maximo 1 elemento en "contenidos" por conceptoId.
                Revisa todos los conceptos del indice antes de decidir omitir uno.
                Lista solo unidades, temas o conceptos nuevos usando tipo, nombre y parentId.
                Si no hay cambios, devuelve {"contenidos":[],"nodos":[]}.
                """;
    }

    /**
     * Fix 5: normalizacion conservadora. Solo se reemplazan comillas
     * tipograficas por ASCII y se eliminan fences Markdown. Se eliminaron
     * las regex multilinea previas, que corrompian descripciones con
     * saltos de linea o patrones "clave: valor".
     */
    private static String normalizeModelCharacters(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw
                .replace('\u2018', '"')
                .replace('\u2019', '"')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u00A0', ' ')
                .trim();

        // Quita fences ```json ... ``` si el modelo las emitio pese al schema.
        if (normalized.startsWith("```")) {
            int firstNewline = normalized.indexOf('\n');
            if (firstNewline > 0) {
                normalized = normalized.substring(firstNewline + 1);
            }
            if (normalized.endsWith("```")) {
                normalized = normalized.substring(0, normalized.length() - 3);
            }
            normalized = normalized.trim();
        }
        return normalized;
    }

    private static void normalizeNodeKeys(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                normalizeNodeKeys(item);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        ObjectNode object = (ObjectNode) node;
        List<Map.Entry<String, JsonNode>> fields =
                new ArrayList<>();
        for (var field : object.properties()) {
            fields.add(field);
        }

        for (var field : fields) {
            String normalized = field.getKey()
                    .trim()
                    .replaceAll("[?'“”‘’]+$", "")
                    .replaceAll("[^A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9_]", "");
            if (!normalized.equals(field.getKey())) {
                object.remove(field.getKey());
                object.set(normalized, field.getValue());
            }
            normalizeNodeKeys(field.getValue());
        }
    }

    private List<String> validatePreservedTree(
            JsonNode original,
            JsonNode generated) {
        List<String> errors = new ArrayList<>();
        validatePreservedNode(original, generated, "$.arbol", errors);
        return List.copyOf(errors);
    }

    private void validatePreservedNode(
            JsonNode original,
            JsonNode generated,
            String path,
            List<String> errors) {
        if (original == null || !original.isObject()) {
            return;
        }
        if (generated == null || !generated.isObject()) {
            errors.add(path + ": falta el nodo generado.");
            return;
        }

        String originalId = text(original, "id");
        String generatedId = text(generated, "id");
        if (!originalId.isBlank() && !originalId.equals(generatedId)) {
            errors.add(path + ".id: no se conserva el nodo existente.");
        }

        String originalName = text(original, "nombre");
        String generatedName = text(generated, "nombre");
        if (!originalName.isBlank()
                && !normalizeName(originalName).equals(normalizeName(generatedName))) {
            errors.add(path + ".nombre: no se conserva el nombre existente.");
        }

        JsonNode originalChildren = original.get("hijos");
        JsonNode generatedChildren = generated.get("hijos");
        if (originalChildren == null || !originalChildren.isArray()) {
            return;
        }
        if (generatedChildren == null || !generatedChildren.isArray()) {
            errors.add(path + ".hijos: falta la lista completa de nodos existentes.");
            return;
        }

        for (int i = 0; i < originalChildren.size(); i++) {
            JsonNode originalChild = originalChildren.get(i);
            String originalChildId = text(originalChild, "id");
            String originalChildName = text(originalChild, "nombre");
            String originalChildType = text(originalChild, "tipo");
            JsonNode generatedChild = findByIdOrNameAndType(
                    generatedChildren,
                    originalChildId,
                    originalChildName,
                    originalChildType);
            if (generatedChild == null) {
                errors.add(path + ".hijos: falta el nodo existente \""
                        + originalChildName + "\".");
                continue;
            }
            validatePreservedNode(
                    originalChild,
                    generatedChild,
                    path + ".hijos",
                    errors);
        }
    }

    private MergeResult mergeOperations(JsonNode original, JsonNode operations) {
        ObjectNode merged = (ObjectNode) objectMapper.valueToTree(original);
        MergeCounter counter = new MergeCounter();
        List<String> errors = new ArrayList<>();
        Set<String> conceptosConContenido = new LinkedHashSet<>();

        JsonNode contenidos = operations.get("contenidos");
        if (contenidos != null && contenidos.isArray()) {
            for (JsonNode item : contenidos) {
                String conceptoId = text(item, "conceptoId");
                String descripcion = text(item, "descripcion");
                JsonNode concepto = findNodeById(merged, conceptoId);
                if (concepto == null
                        || !"concepto".equalsIgnoreCase(text(concepto, "tipo"))) {
                    errors.add("conceptoId inexistente: " + conceptoId);
                    continue;
                }
                if (descripcion.isBlank()) {
                    continue;
                }
                if (!conceptosConContenido.add(conceptoId)) {
                    continue;
                }
                addGeneratedContent((ObjectNode) concepto, descripcion, counter);
            }
        }

        JsonNode nodos = operations.get("nodos");
        if (nodos != null && nodos.isArray()) {
            Map<String, JsonNode> nodesByReference = new java.util.LinkedHashMap<>();
            indexNodeReferences(merged, nodesByReference);
            List<JsonNode> pendingNodes = new ArrayList<>();
            Set<String> references = new LinkedHashSet<>(nodesByReference.keySet());
            for (JsonNode item : nodos) {
                String tempId = text(item, "tempId");
                if (!references.add(tempId)) {
                    errors.add("tempId duplicado o en conflicto: " + tempId);
                }
                pendingNodes.add(item);
            }

            if (errors.isEmpty()) {
                while (!pendingNodes.isEmpty()) {
                    boolean resolvedNode = false;
                    for (int i = 0; i < pendingNodes.size();) {
                        JsonNode item = pendingNodes.get(i);
                        String parentId = text(item, "parentId");
                        String tempId = text(item, "tempId");
                        String type = text(item, "tipo");
                        String name = text(item, "nombre");
                        JsonNode parent = nodesByReference.get(parentId);
                        if (parent == null) {
                            i++;
                            continue;
                        }
                        if (!(parent instanceof ObjectNode parentObject)
                                || !isValidParentType(parent, type)) {
                            errors.add("parentId invalido para " + type + ": " + parentId);
                            pendingNodes.remove(i);
                            resolvedNode = true;
                            continue;
                        }

                        JsonNode duplicate = findChildByName(parent, type, name);
                        if (duplicate != null) {
                            nodesByReference.put(tempId, duplicate);
                            pendingNodes.remove(i);
                            resolvedNode = true;
                            continue;
                        }

                        ObjectNode newNode = objectMapper.createObjectNode();
                        newNode.put("id", "TEMP-" + UUID.randomUUID());
                        newNode.put("tipo", type);
                        newNode.put("nombre", name);
                        newNode.put("origen", "ia");
                        newNode.put("revisionEstado", "pendiente_revision");
                        newNode.put("dirty", true);
                        newNode.putArray("contenido");
                        newNode.putArray("hijos");

                        JsonNode contenido = item.get("contenido");
                        if (contenido != null && contenido.isArray()) {
                            for (JsonNode content : contenido) {
                                String descripcion = text(content, "descripcion");
                                if (!descripcion.isBlank()) {
                                    addGeneratedContent(newNode, descripcion, counter);
                                }
                            }
                        }

                        ensureChildren(parentObject).add(newNode);
                        nodesByReference.put(tempId, newNode);
                        counter.nodosAgregados++;
                        pendingNodes.remove(i);
                        resolvedNode = true;
                    }
                    if (!resolvedNode) {
                        for (JsonNode item : pendingNodes) {
                            errors.add("parentId no encontrado para " + text(item, "tempId")
                                    + ": " + text(item, "parentId"));
                        }
                        break;
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidCurriculumOutputException(
                    "El modelo devolvio operaciones con referencias invalidas.",
                    errors);
        }
        return new MergeResult(merged,
                counter.nodosAgregados,
                counter.contenidosAgregados);
    }

    private JsonNode findNodeById(JsonNode node, String id) {
        if (node == null || id.isBlank()) {
            return null;
        }
        if (id.equals(text(node, "id"))) {
            return node;
        }
        JsonNode children = node.get("hijos");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                JsonNode found = findNodeById(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean isValidParentType(JsonNode parent, String childType) {
        String parentType = text(parent, "tipo");
        return ("unidad".equals(childType) && "materia".equals(parentType))
                || ("tema".equals(childType) && "unidad".equals(parentType))
                || ("concepto".equals(childType) && "tema".equals(parentType));
    }

    private JsonNode findChildByName(JsonNode parent, String type, String name) {
        JsonNode children = parent.get("hijos");
        if (children == null || !children.isArray()) {
            return null;
        }
        for (JsonNode child : children) {
            if (normalizeName(type).equals(normalizeName(text(child, "tipo")))
                    && normalizeName(name).equals(normalizeName(text(child, "nombre")))) {
                return child;
            }
        }
        return null;
    }

    private void indexNodeReferences(
            JsonNode node,
            Map<String, JsonNode> references) {
        String id = text(node, "id");
        if (!id.isBlank()) {
            references.putIfAbsent(id, node);
        }
        JsonNode children = node.get("hijos");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                indexNodeReferences(child, references);
            }
        }
    }

    private void addGeneratedContent(
            ObjectNode node,
            String descripcion,
            MergeCounter counter) {
        ArrayNode contenido = node.get("contenido") instanceof ArrayNode array
                ? array
                : node.putArray("contenido");
        if (containsDescription(contenido, descripcion)) {
            return;
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", "TEMP-" + UUID.randomUUID());
        item.put("descripcion", descripcion);
        item.put("origen", "ia");
        item.put("revisionEstado", "pendiente_revision");
        contenido.add(item);
        counter.contenidosAgregados++;
        if ("original".equalsIgnoreCase(text(node, "revisionEstado"))
                || text(node, "revisionEstado").isBlank()) {
            node.put("revisionEstado", "modificado");
        }
        node.put("dirty", true);
        if ("concepto".equalsIgnoreCase(text(node, "tipo"))) {
            node.put("estadoConcepto", "con_contenido");
        }
    }

    private JsonNode findByIdOrNameAndType(
            JsonNode children,
            String id,
            String name,
            String type) {
        if (children == null || !children.isArray()) {
            return null;
        }
        for (JsonNode child : children) {
            if (!id.isBlank() && id.equals(text(child, "id"))) {
                return child;
            }
            if (normalizeName(name).equals(normalizeName(text(child, "nombre")))
                    && normalizeName(type).equals(normalizeName(text(child, "tipo")))) {
                return child;
            }
        }
        return null;
    }

    private MergeResult mergeTemporary(JsonNode original, JsonNode generated) {
        ObjectNode merged = (ObjectNode) objectMapper.valueToTree(original);
        MergeCounter counter = new MergeCounter();
        mergeNode(merged, generated, counter, true, getMateriaNombre(original));
        return new MergeResult(merged, counter.nodosAgregados,
                counter.contenidosAgregados);
    }

    private int countNodes(JsonNode node) {
        if (node == null || !node.isObject()) {
            return 0;
        }
        int count = 1;
        JsonNode children = node.get("hijos");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                count += countNodes(child);
            }
        }
        return count;
    }

    private String getMateriaNombre(JsonNode original) {
        return text(original, "nombre");
    }

    private JsonNode compactCurriculumTree(JsonNode node) {
        if (node == null || !node.isObject()) {
            return objectMapper.createObjectNode();
        }

        ObjectNode compact = objectMapper.createObjectNode();
        String type = text(node, "tipo");
        String name = text(node, "nombre");
        if (!type.isBlank()) {
            compact.put("tipo", type);
        }
        if (!name.isBlank()) {
            compact.put("nombre", name);
        }

        JsonNode estado = node.get("estadoConcepto");
        if (estado != null && estado.isString()) {
            compact.put("estadoConcepto", estado.asString());
        }

        ArrayNode contenido = compact.putArray("contenido");
        JsonNode originalContent = node.get("contenido");
        if (originalContent != null && originalContent.isArray()) {
            int included = 0;
            for (JsonNode item : originalContent) {
                if (included >= 3) {
                    break;
                }
                String description = text(item, "descripcion");
                if (description.isBlank()) {
                    continue;
                }
                if (description.length() > 160) {
                    description = description.substring(0, 160).trim();
                }
                contenido.addObject().put("descripcion", description);
                included++;
            }
        }

        ArrayNode hijos = compact.putArray("hijos");
        JsonNode originalChildren = node.get("hijos");
        if (originalChildren != null && originalChildren.isArray()) {
            for (JsonNode child : originalChildren) {
                hijos.add(compactCurriculumTree(child));
            }
        }
        return compact;
    }

    private String collectNodeNames(JsonNode node) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectNodeNames(node, names);
        if (names.isEmpty()) {
            return "";
        }
        List<String> limited = new ArrayList<>(names);
        if (limited.size() > 20) {
            limited = limited.subList(0, 20);
        }
        return String.join(" | ", limited);
    }

    private void collectNodeNames(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject()) {
            return;
        }
        String name = text(node, "nombre");
        if (!name.isBlank()) {
            names.add(name);
        }
        JsonNode children = node.get("hijos");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                collectNodeNames(child, names);
            }
        }
    }

    private void mergeNode(
            ObjectNode original,
            JsonNode generated,
            MergeCounter counter,
            boolean isRoot,
            String materiaNombre) {
        JsonNode generatedChildren = generated.get("hijos");
        if (generatedChildren == null || !generatedChildren.isArray()) {
            mergeContent(original, generated, counter);
            return;
        }

        mergeContent(original, generated, counter);
        ArrayNode originalChildren = ensureChildren(original);
        for (JsonNode generatedChild : generatedChildren) {
            String generatedName = text(generatedChild, "nombre");
            String generatedType = text(generatedChild, "tipo");
            if (generatedName.isBlank()) {
                continue;
            }
            if ("materia".equalsIgnoreCase(generatedType)) {
                continue;
            }
            if (!"materia".equalsIgnoreCase(generatedType)
                    && normalizeName(generatedName).equals(normalizeName(materiaNombre))) {
                continue;
            }
            JsonNode existing = findChildByNameAndType(originalChildren, generatedChild);
            if (existing == null) {
                ObjectNode newNode = normalizeNewNode(generatedChild);
                originalChildren.add(newNode);
                counter.nodosAgregados++;
                countGeneratedContent(newNode, counter);
                continue;
            }

            ObjectNode existingNode = (ObjectNode) existing;
            mergeNode(existingNode, generatedChild, counter, false, materiaNombre);
        }
    }

    private void mergeContent(
            ObjectNode original,
            JsonNode generated,
            MergeCounter counter) {
        JsonNode generatedContent = generated.get("contenido");
        if (generatedContent == null || !generatedContent.isArray()
                || generatedContent.isEmpty()) {
            return;
        }

        ArrayNode originalContent = original.get("contenido") instanceof ArrayNode array
                ? array
                : original.putArray("contenido");

        boolean changed = false;
        for (JsonNode content : generatedContent) {
            String description = text(content, "descripcion");
            if (description.isBlank() || containsDescription(originalContent, description)) {
                continue;
            }
            ObjectNode newContent = objectMapper.createObjectNode();
            newContent.put("id", "TEMP-" + UUID.randomUUID());
            newContent.put("descripcion", description);
            newContent.put("origen", "ia");
            newContent.put("revisionEstado", "pendiente_revision");
            originalContent.add(newContent);
            counter.contenidosAgregados++;
            changed = true;
        }
        if (changed) {
            original.put("revisionEstado", "modificado");
            original.put("dirty", true);
            if ("concepto".equalsIgnoreCase(text(original, "tipo"))) {
                original.put("estadoConcepto", "con_contenido");
            }
        }
    }

    private ObjectNode normalizeNewNode(JsonNode generated) {
        ObjectNode node = (ObjectNode) objectMapper.valueToTree(generated);
        node.put("id", "TEMP-" + UUID.randomUUID());
        node.put("origen", "ia");
        node.put("revisionEstado", "pendiente_revision");
        node.put("dirty", true);

        JsonNode generatedContent = node.get("contenido");
        if (generatedContent instanceof ArrayNode contentArray) {
            for (JsonNode content : contentArray) {
                if (content instanceof ObjectNode contentNode) {
                    if (!contentNode.has("id")) {
                        contentNode.put("id", "TEMP-" + UUID.randomUUID());
                    }
                    contentNode.put("origen", "ia");
                    contentNode.put("revisionEstado", "pendiente_revision");
                }
            }
        }

        JsonNode children = node.get("hijos");
        if (children instanceof ArrayNode childrenArray) {
            for (JsonNode child : childrenArray) {
                normalizeNewDescendant((ObjectNode) child);
            }
        }
        return node;
    }

    private void normalizeNewDescendant(ObjectNode node) {
        node.put("id", "TEMP-" + UUID.randomUUID());
        node.put("origen", "ia");
        node.put("revisionEstado", "pendiente_revision");
        node.put("dirty", true);
        JsonNode children = node.get("hijos");
        if (children instanceof ArrayNode array) {
            for (JsonNode child : array) {
                if (child instanceof ObjectNode childNode) {
                    normalizeNewDescendant(childNode);
                }
            }
        }
    }

    private void countGeneratedContent(ObjectNode node, MergeCounter counter) {
        JsonNode content = node.get("contenido");
        if (content instanceof ArrayNode array) {
            counter.contenidosAgregados += array.size();
        }
        JsonNode children = node.get("hijos");
        if (children instanceof ArrayNode array) {
            for (JsonNode child : array) {
                if (child instanceof ObjectNode childNode) {
                    countGeneratedContent(childNode, counter);
                }
            }
        }
    }

    private ArrayNode ensureChildren(ObjectNode node) {
        JsonNode children = node.get("hijos");
        if (children instanceof ArrayNode array) {
            return array;
        }
        return node.putArray("hijos");
    }

    private JsonNode findChildByNameAndType(
            ArrayNode children,
            JsonNode candidate) {
        String candidateName = text(candidate, "nombre");
        String candidateType = text(candidate, "tipo");
        for (JsonNode child : children) {
            if (child == null || !child.isObject()) {
                continue;
            }
            String childName = normalizeName(text(child, "nombre"));
            String typeMatches = text(child, "tipo");
            if (normalizeName(candidateName).equals(childName)
                    && (candidateType.isBlank()
                            || candidateType.equalsIgnoreCase(typeMatches))) {
                return child;
            }
        }
        return null;
    }

    private boolean containsDescription(ArrayNode content, String description) {
        String normalizedDescription = normalizeName(description);
        for (JsonNode item : content) {
            if (normalizedDescription.equals(normalizeName(text(item, "descripcion")))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode value = node.get(field);
        return value != null && value.isString()
                ? value.asString().trim()
                : "";
    }

    private String sourceKey(String documentoId, String checksum) {
        return documentoId + ":" + checksum.toLowerCase(Locale.ROOT);
    }

    public static class InvalidCurriculumTreeException extends RuntimeException {
        private final List<String> validationErrors;

        public InvalidCurriculumTreeException(String message) {
            this(message, List.of());
        }

        public InvalidCurriculumTreeException(String message, List<String> validationErrors) {
            super(message);
            this.validationErrors = List.copyOf(validationErrors);
        }

        public List<String> getValidationErrors() {
            return validationErrors;
        }
    }

    public static class InvalidCurriculumOutputException extends RuntimeException {
        private final List<String> validationErrors;

        public InvalidCurriculumOutputException(
                String message,
                List<String> validationErrors) {
            super(message);
            this.validationErrors = List.copyOf(validationErrors);
        }

        public List<String> getValidationErrors() {
            return validationErrors;
        }
    }

    public static class NoRelevantCurriculumException extends RuntimeException {
        public NoRelevantCurriculumException(String message) {
            super(message);
        }
    }

    private record MergeResult(
            JsonNode arbol,
            int nodosAgregados,
            int contenidosAgregados) {
    }

    private static class MergeCounter {
        int nodosAgregados;
        int contenidosAgregados;
    }
}