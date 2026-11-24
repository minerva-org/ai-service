package com.minerva.aiservice.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.minerva.aiservice.dto.CurriculumGenerationRequest;
import com.minerva.aiservice.dto.CurriculumGenerationResponse;
import com.minerva.aiservice.dto.QuizGenerationRequest;
import com.minerva.aiservice.dto.QuizGenerationResponse;
import com.minerva.aiservice.dto.TaskDtos;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskStoreService {
    private static final long TTL_SECONDS = 30 * 60;
    private static final Logger LOGGER =
            Logger.getLogger(TaskStoreService.class.getName());

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CurriculumGenerationService curriculumGenerationService;
    private final QuizGenerationService quizGenerationService;
    private final ObjectMapper objectMapper;

    public TaskStoreService(
            CurriculumGenerationService curriculumGenerationService,
            QuizGenerationService quizGenerationService,
            ObjectMapper objectMapper) {
        this.curriculumGenerationService = curriculumGenerationService;
        this.quizGenerationService = quizGenerationService;
        this.objectMapper = objectMapper;
    }

    public TaskDtos.CreateResponse create(TaskDtos.CreateRequest request) {
        if (request == null
                || request.tipo() == null
                || request.datos() == null
                || !request.datos().isObject()) {
            throw new InvalidTaskRequestException(
                    "Tipo de tarea no valido o datos incompletos.");
        }
        String tipo = request.tipo().toUpperCase(Locale.ROOT);
        if ("CURRICULUM_GENERATE".equals(tipo)) {
            curriculumRequest(request.datos());
        } else if ("QUIZ_GENERATE".equals(tipo)) {
            quizRequest(request.datos());
        } else {
            throw new InvalidTaskRequestException("Tipo de tarea no valido.");
        }

        String tareaId = UUID.randomUUID().toString();
        TaskRecord record = new TaskRecord(
                tareaId,
                request.tipo().toUpperCase(Locale.ROOT),
                "EN_COLA",
                "Esperando procesamiento",
                null,
                null,
                Instant.now().plusSeconds(TTL_SECONDS),
                null);
        tasks.put(tareaId, record);
        LOGGER.log(Level.INFO,
                "TAREA_CREADA: tareaId={0}, tipo={1}",
                new Object[]{tareaId, record.tipo});

        Future<?> future = executor.submit(() -> {
            if ("QUIZ_GENERATE".equals(record.tipo)) {
                runQuizTask(record, request.datos());
            } else {
                runCurriculumTask(record, request.datos());
            }
        });
        record.future = future;
        return new TaskDtos.CreateResponse(tareaId);
    }

    public TaskDtos.StatusResponse get(String tareaId) {
        purgeExpired();
        TaskRecord record = tasks.get(tareaId);
        if (record == null) {
            throw new TaskNotFoundException(
                    "La tarea no existe o ya vencio.");
        }
        return record.toStatus();
    }

    public void delete(String tareaId) {
        TaskRecord record = tasks.get(tareaId);
        if (record == null) {
            return;
        }
        if (record.future != null) {
            record.future.cancel(true);
        }
        tasks.remove(tareaId);
        LOGGER.log(Level.INFO,
                "TAREA_CANCELADA: tareaId={0}, tipo={1}",
                new Object[]{tareaId, record.tipo});
    }

    private void runQuizTask(TaskRecord record, JsonNode datos) {
        update(record.tareaId, "PROCESANDO",
                "Analizando conceptos con contenido", null, null);
        try {
            QuizGenerationRequest request = quizRequest(datos);
            LOGGER.log(Level.INFO,
                    "QUIZ_TAREA_INICIO: tareaId={0}, conceptos={1}, promptChars={2}",
                    new Object[]{record.tareaId,
                            request.concepts().size(),
                            request.prompt().length()});
            update(record.tareaId, "PROCESANDO",
                    "Generando preguntas y distractores", null, null);
            QuizGenerationResponse response =
                    quizGenerationService.generate(request);
            LOGGER.log(Level.INFO,
                    "QUIZ_TAREA_COMPLETADA: tareaId={0}, preguntas={1}",
                    new Object[]{record.tareaId,
                            response.questions().size()});
            update(record.tareaId, "COMPLETADA",
                    "Preguntas generadas", null, response);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING,
                    "TAREA_ERROR: tareaId={0}, tipoError={1}, mensaje={2}",
                    new Object[]{record.tareaId,
                            exception.getClass().getSimpleName(),
                            exception.getMessage()});
            update(record.tareaId, "ERROR",
                    exception.getMessage(), null, null);
        }
    }

    private void runCurriculumTask(TaskRecord record, JsonNode datos) {
        update(record.tareaId, "PROCESANDO",
                "Extrayendo texto y reconociendo secciones", null, null);
        try {
            CurriculumGenerationRequest request =
                    curriculumRequest(datos);
            MultipartFile[] files = curriculumFiles(datos);
            var response =
                    curriculumGenerationService.generate(
                            request,
                            files,
                            progress -> update(
                                    record.tareaId,
                                    "PROCESANDO",
                                    progress,
                                    null,
                                    null));
            if (!response.errores().isEmpty()
                    && response.fuentesProcesadas().isEmpty()) {
                String errorMessage = response.errores().stream()
                        .map(CurriculumGenerationResponse.ErrorDetalle::mensaje)
                        .filter(message -> message != null && !message.isBlank())
                        .findFirst()
                        .orElse("La generacion termino con un error.");
                update(record.tareaId, "ERROR",
                        null, errorMessage, null);
                return;
            }
            update(record.tareaId, "COMPLETADA",
                    "Generacion terminada", null, response);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING,
                    "TAREA_ERROR: tareaId={0}, tipoError={1}, mensaje={2}",
                    new Object[]{record.tareaId,
                            exception.getClass().getSimpleName(),
                            exception.getMessage()});
            update(record.tareaId, "ERROR",
                    null, exception.getMessage(), null);
        }
    }

    private QuizGenerationRequest quizRequest(JsonNode datos) {
        String prompt = text(datos, "prompt");
        if (prompt.isBlank()) {
            throw new InvalidTaskRequestException(
                    "El campo datos.prompt es obligatorio.");
        }
        JsonNode arbol = datos.get("arbol");
        if (arbol == null || !arbol.isObject()) {
            throw new InvalidTaskRequestException(
                    "El campo datos.arbol es obligatorio.");
        }

        List<QuizGenerationRequest.ConceptInput> concepts =
                new ArrayList<>();
        collectEligibleQuizConcepts(arbol, concepts);
        if (concepts.isEmpty()) {
            throw new InvalidTaskRequestException(
                    "No hay conceptos con contenido relacionado.");
        }
        return new QuizGenerationRequest(prompt, concepts);
    }

    private void collectEligibleQuizConcepts(
            JsonNode node,
            List<QuizGenerationRequest.ConceptInput> concepts) {
        if (node == null || !node.isObject()) {
            return;
        }
        boolean isConcept = "concepto".equalsIgnoreCase(text(node, "tipo"))
                || (node.has("contenidos") && node.get("contenidos").isArray());
        if (isConcept) {
            String id = text(node, "id");
            String name = text(node, "nombre");
            JsonNode contenido = node.get("contenido");
            if (contenido == null) {
                contenido = node.get("contenidos");
            }
            if (!id.isBlank() && !name.isBlank()
                    && contenido != null && contenido.isArray()
                    && !contenido.isEmpty()) {
                List<QuizGenerationRequest.ContentInput> content =
                        new ArrayList<>();
                for (JsonNode item : contenido) {
                    String description = text(item, "descripcion");
                    if (!description.isBlank()) {
                        content.add(new QuizGenerationRequest.ContentInput(
                                description));
                    }
                }
                if (content.isEmpty()) {
                    return;
                }
                concepts.add(
                        new QuizGenerationRequest.ConceptInput(
                                id, name, content));
            }
        }
        collectQuizChildren(node.get("hijos"), concepts);
        collectQuizChildren(node.get("unidades"), concepts);
        collectQuizChildren(node.get("temas"), concepts);
        collectQuizChildren(node.get("conceptos"), concepts);
    }

    private void collectQuizChildren(
            JsonNode children,
            List<QuizGenerationRequest.ConceptInput> concepts) {
        if (children == null || !children.isArray()) {
            return;
        }
        for (JsonNode child : children) {
            collectEligibleQuizConcepts(child, concepts);
        }
    }

    private CurriculumGenerationRequest curriculumRequest(JsonNode datos) {
        JsonNode arbol = datos.get("arbol");
        if (arbol == null || !arbol.isObject()) {
            throw new InvalidTaskRequestException(
                    "El campo datos.arbol es obligatorio.");
        }

        String grupoId = text(datos, "grupoId");
        if (grupoId.isBlank()) {
            throw new InvalidTaskRequestException(
                    "El campo datos.grupoId es obligatorio.");
        }
        String materiaNombre = text(datos, "materiaNombre");
        if (materiaNombre.isBlank()) {
            JsonNode root = datos.get("arbol");
            materiaNombre = root != null && root.isObject()
                    ? text(root, "nombre")
                    : "";
        }
        if (materiaNombre.isBlank()) {
            throw new InvalidTaskRequestException(
                    "El campo datos.materiaNombre es obligatorio.");
        }

        List<CurriculumGenerationRequest.FuenteNueva> fuentes =
                new ArrayList<>();
        JsonNode fuentesNode = datos.get("fuentes");
        if (fuentesNode != null && fuentesNode.isArray()) {
            for (JsonNode fuente : fuentesNode) {
                fuentes.add(new CurriculumGenerationRequest.FuenteNueva(
                        text(fuente, "documentoId"),
                        text(fuente, "checksumSha256"),
                        text(fuente, "nombre")));
            }
        }

        List<CurriculumGenerationRequest.FuenteProcesada> processed =
                new ArrayList<>();
        JsonNode processedNode = datos.get("fuentesProcesadas");
        if (processedNode != null && processedNode.isArray()) {
            for (JsonNode fuente : processedNode) {
                processed.add(new CurriculumGenerationRequest.FuenteProcesada(
                        text(fuente, "documentoId"),
                        text(fuente, "checksumSha256")));
            }
        }

        return new CurriculumGenerationRequest(
                grupoId,
                arbol,
                materiaNombre,
                fuentes,
                processed,
                text(datos, "modoFusion"));
    }

    private MultipartFile[] curriculumFiles(JsonNode datos) {
        JsonNode archivos = datos.get("archivos");
        if (archivos == null || !archivos.isArray()) {
            return new MultipartFile[0];
        }

        List<MultipartFile> files = new ArrayList<>();
        for (JsonNode archivo : archivos) {
            String nombre = text(archivo, "nombre");
            String base64 = text(archivo, "contenidoBase64");
            if (nombre.isBlank() || base64.isBlank()) {
                continue;
            }
            byte[] content;
            try {
                content = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException exception) {
                throw new InvalidTaskRequestException(
                        "contenidoBase64 no es una cadena base64 valida.");
            }
            files.add(new ByteArrayMultipartFile(
                    "files",
                    nombre,
                    "application/pdf",
                    content));
        }
        return files.toArray(MultipartFile[]::new);
    }

    private void update(
            String tareaId,
            String estado,
            String progreso,
            String error,
            Object resultado) {
        tasks.computeIfPresent(tareaId, (id, record) ->
                new TaskRecord(
                        record.tareaId,
                        record.tipo,
                        estado,
                        progreso,
                        error,
                        resultado == null
                                ? record.resultado
                                : objectMapper.valueToTree(resultado),
                        record.expiresAt,
                        record.future));
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tasks.entrySet().removeIf(entry ->
                entry.getValue().expiresAt.isBefore(now));
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode value = node.get(field);
        return value != null && value.isString()
                ? value.asString().trim()
                : "";
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    public static class InvalidTaskRequestException extends RuntimeException {
        public InvalidTaskRequestException(String message) {
            super(message);
        }
    }

    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String message) {
            super(message);
        }
    }

    private static final class TaskRecord {
        private final String tareaId;
        private final String tipo;
        private final String estado;
        private final String progreso;
        private final String error;
        private final JsonNode resultado;
        private final Instant expiresAt;
        private Future<?> future;

        private TaskRecord(
                String tareaId,
                String tipo,
                String estado,
                String progreso,
                String error,
                JsonNode resultado,
                Instant expiresAt,
                Future<?> future) {
            this.tareaId = tareaId;
            this.tipo = tipo;
            this.estado = estado;
            this.progreso = progreso;
            this.error = error;
            this.resultado = resultado;
            this.expiresAt = expiresAt;
            this.future = future;
        }

        private TaskDtos.StatusResponse toStatus() {
            return new TaskDtos.StatusResponse(
                    tareaId,
                    tipo,
                    estado,
                    progreso,
                    resultado,
                    error);
        }
    }

    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        private ByteArrayMultipartFile(
                String name,
                String originalFilename,
                String contentType,
                byte[] content) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
