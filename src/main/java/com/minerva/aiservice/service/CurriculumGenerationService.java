package com.minerva.aiservice.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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

    private final PdfTextExtractor pdfTextExtractor;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public CurriculumGenerationResponse generate(
            CurriculumGenerationRequest request,
            MultipartFile[] files) {

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
                String text = pdfTextExtractor.extract(file);
                if (text == null || text.isBlank()) {
                    sourceResults.add(failedSource(fuente,
                            "La fuente no contiene texto legible."));
                    continue;
                }
                sourceTexts.add(
                        "Fuente: " + fuente.nombre() + "\n"
                                + "documentoId: " + fuente.documentoId() + "\n"
                                + "<document>\n" + text + "\n</document>");
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
        String raw = ollamaClient.generateStructured(
                system,
                user,
                CurriculumGenerationSchema.schema());

        JsonNode generatedTree;
        try {
            generatedTree = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(raw);
        } catch (JacksonException exception) {
            throw new OllamaClient.InvalidModelResponseException(
                    "Ollama returned invalid JSON.", exception);
        }

        List<String> generatedErrors =
                CurriculumGenerationSchema.validate(generatedTree);
        if (!generatedErrors.isEmpty()) {
            throw new InvalidCurriculumOutputException(
                    "El modelo devolvio un arbol curricular invalido.",
                    generatedErrors);
        }

        MergeResult mergeResult = mergeTemporary(request.arbol(), generatedTree);
        if (mergeResult.nodosAgregados() == 0
                && mergeResult.contenidosAgregados() == 0) {
            throw new NoRelevantCurriculumException(
                    "No se encontro informacion curricular relevante en las fuentes nuevas.");
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
                - No inventes informacion no presente en las fuentes.
                - Agrega solo informacion curricular relevante.
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
                - modoFusion: conservadora

                Arbol actual:
                """ + request.arbol().toString() + """

                Fuentes nuevas:
                """ + String.join("\n\n", sourceTexts) + """

                Tarea:
                Analiza solo las fuentes nuevas.
                Detecta unidades, temas y conceptos relevantes.
                Agrega contenido breve y verificable.
                Devuelve el arbol completo actualizado.
                No persistiras cambios.
                No debes devolver explicaciones fuera del JSON.
                """;
    }

    private MergeResult mergeTemporary(JsonNode original, JsonNode generated) {
        ObjectNode merged = (ObjectNode) objectMapper.valueToTree(original);
        MergeCounter counter = new MergeCounter();
        mergeNode(merged, generated, counter, true);
        return new MergeResult(merged, counter.nodosAgregados,
                counter.contenidosAgregados);
    }

    private void mergeNode(
            ObjectNode original,
            JsonNode generated,
            MergeCounter counter,
            boolean isRoot) {
        JsonNode generatedChildren = generated.get("hijos");
        if (generatedChildren == null || !generatedChildren.isArray()) {
            mergeContent(original, generated, counter);
            return;
        }

        mergeContent(original, generated, counter);
        ArrayNode originalChildren = ensureChildren(original);
        for (JsonNode generatedChild : generatedChildren) {
            String generatedName = text(generatedChild, "nombre");
            if (generatedName.isBlank()) {
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
            mergeNode(existingNode, generatedChild, counter, false);
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
            if (candidateName.equalsIgnoreCase(text(child, "nombre"))
                    && (candidateType.isBlank()
                            || candidateType.equalsIgnoreCase(text(child, "tipo")))) {
                return child;
            }
        }
        return null;
    }

    private boolean containsDescription(ArrayNode content, String description) {
        for (JsonNode item : content) {
            if (description.equalsIgnoreCase(text(item, "descripcion"))) {
                return true;
            }
        }
        return false;
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
