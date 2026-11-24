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
    private final PdfTextExtractor pdfTextExtractor;
    private final OllamaClient ollamaClient;
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

        List<String> arbolErrors = CurriculumGenerationSchema.validate(request.arbol());
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
                        "No se encontro el archivo PDF asociado a la fuente."));
                continue;
            }
            unusedFiles.remove(file);

            try {
                progress.accept("Extrayendo texto de " + fuente.nombre());
                String text = pdfTextExtractor.extract(file);
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
        String raw = ollamaClient.generateStructured(
                system,
                user,
                CurriculumGenerationSchema.schema());

        JsonNode generatedTree;
        try {
            progress.accept("Validando la respuesta del modelo");
            generatedTree = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(raw);
            normalizeNodeKeys(generatedTree);
        } catch (JacksonException exception) {
            LOGGER.log(Level.WARNING,
                    "RESPUESTA_MODELO: jsonInvalido, respuestaChars={0}, tipoError={1}",
                    new Object[]{raw == null ? 0 : raw.length(),
                            exception.getClass().getSimpleName()});
            throw new OllamaClient.InvalidModelResponseException(
                    "Ollama returned invalid JSON.", exception);
        }

        List<String> generatedErrors =
                CurriculumGenerationSchema.validate(generatedTree);
        if (!generatedErrors.isEmpty()) {
            LOGGER.log(Level.WARNING,
                    "RESPUESTA_MODELO: contratoInvalido, errores={0}",
                    generatedErrors.size());
            throw new InvalidCurriculumOutputException(
                    "El modelo devolvio un arbol curricular invalido.",
                    generatedErrors);
        }

        progress.accept("Fusionando cambios conservadores con el arbol actual");
        MergeResult mergeResult = mergeTemporary(request.arbol(), generatedTree);
        LOGGER.log(Level.INFO,
                "MERGE_CURRICULO: nodosGenerados={0}, nodosAgregados={1}, contenidosAgregados={2}, nombresGenerados={3}",
                new Object[]{countNodes(generatedTree),
                        mergeResult.nodosAgregados(),
                        mergeResult.contenidosAgregados(),
                        collectNodeNames(generatedTree)});
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
                        0,
                        0,
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
                Eres un asistente de construccion curricular.
                Recibiras un arbol JSON existente y fuentes nuevas.
                Alimenta el arbol sin destruirlo.

                Reglas obligatorias:
                - Conserva todos los nodos persistidos.
                - Conserva nombres manuales.
                - No elimines unidades, temas ni conceptos.
                - No crees ni dupliques la materia raiz.
                - No conviertas el nombre de la materia en una unidad, tema o concepto.
                - Materia solo puede aparecer una vez como raiz y debe conservar el nombre recibido.
                - No infieras una unidad, tema o concepto nuevo solo porque repita el nombre de la materia.
                - No inventes informacion no presente en las fuentes.
                - Agrega solo informacion curricular relevante.
                - Para conceptos existentes que tengan informacion verificable en las fuentes,
                  agrega entre 2 y 5 descripciones breves a su campo "contenido".
                - Cada elemento de "contenido" debe ser un objeto con
                  "descripcion" como texto, no una cadena directa.
                - Si una fuente trata conceptos presentes en el arbol, relaciona la
                  informacion especifica con esos conceptos por nombre y jerarquia.
                - No fusiones nodos por nombre a menos que tengan el mismo significado evidente.
                - Marca cada nodo agregado con origen "ia".
                - Marca cada nodo agregado con revisionEstado "pendiente_revision".
                - Devuelve exclusivamente JSON valido conforme al contrato.
                """;
    }

    private String threeLayerContextPrompt(
            CurriculumGenerationRequest request,
            List<String> sourceTexts) {
        return """
                Contexto:
                - grupoId: """ + request.grupoId() + """
                - materiaNombre: """ + request.materiaNombre() + """
                - modoFusion: conservadora

                Arbol actual:
                """ + request.arbol().toString() + """

                Fuentes nuevas:
                """ + String.join("\n\n", sourceTexts) + """

                Tarea:
                Analiza solo las fuentes nuevas.
                Detecta unidades, temas y conceptos relevantes.
                Relaciona primero los nombres del arbol con las secciones de las fuentes.
                Agrega contenido breve y verificable a los conceptos coincidentes.
                Agrega unidades, temas o conceptos nuevos solo cuando la fuente los
                identifique claramente y no exista una coincidencia equivalente.
                Devuelve el arbol completo actualizado.
                Usa exactamente las claves "tipo", "nombre", "contenido" y "hijos".
                No agregues espacios al final de los nombres de claves.
                No persistiras cambios.
                No debes devolver explicaciones fuera del JSON.
                """;
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
            String normalized = field.getKey().trim();
            if (!normalized.equals(field.getKey())) {
                object.remove(field.getKey());
                object.set(normalized, field.getValue());
            }
            normalizeNodeKeys(field.getValue());
        }
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
