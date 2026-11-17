# Minerva AI-Service

Servicio Spring Boot privado para desglosar documentos PDF, generar preguntas de quiz e interpretar contexto estadístico preparado por Minerva. Ollama y el modelo se ejecutan en el host; el servicio no accede a PostgreSQL ni a S3/MinIO.

## Requisitos

- Java 25.
- Maven 3.9 o posterior.
- Ollama instalado y ejecutándose con `qwen3:4b` disponible.
- Minerva API autentica al usuario y autoriza el ámbito antes de llamar a este servicio.

## Configuración local en Windows

Desde PowerShell en la raíz del proyecto:

```powershell
Copy-Item .env.example .env
```

Genera un secreto de al menos 32 caracteres y colócalo en `AI_SERVICE_API_KEY` dentro de `.env`. Minerva API deberá enviar el mismo valor en el header `X-AI-Service-Key`. No compartas ni subas el `.env`.

Configuración predeterminada: Ollama en `http://127.0.0.1:11434`, modelo `qwen3:4b`, AI-Service en el puerto `8000`. La Minerva API en Docker Desktop puede acceder al AI-Service del host mediante `http://host.docker.internal:8000`.

Inicia el servicio:

```powershell
mvn spring-boot:run
```

El servidor escucha en las interfaces del host para aceptar la conexión desde Docker. Mantén el puerto 8000 bloqueado a redes no confiables en Windows Firewall. No abras puertos del router ni publiques este servicio u Ollama mediante Tailscale Funnel.

## API

### Estado del modelo

`GET /health` consulta Ollama y confirma que el modelo configurado esté instalado. Devuelve `200` si está disponible y `503` si Ollama no responde o falta el modelo. El endpoint no requiere la clave interna; restríngele el acceso mediante la red/firewall.

### Desglosar PDF

`POST /documents/extract`

Requiere `X-AI-Service-Key` y `multipart/form-data` con campo `file`. Acepta PDF de hasta 10 MiB, extrae texto seleccionable sin OCR y devuelve `units → topics → concepts`.

### Generar quiz

`POST /quizzes/generate`

Requiere `X-AI-Service-Key`. Recibe un JSON con `prompt` y `concepts`; devuelve preguntas con opciones y exactamente una opción correcta por pregunta.

### Interpretar estadísticas

`POST /statistics/interpret`

Requiere `X-AI-Service-Key`. Recibe `prompt` y `screenContext` ya autorizado y agregado por Minerva. El modelo interpreta únicamente ese contexto; no accede a datos ni inventa métricas.

La clave de servicio es solo para comunicación backend-a-backend. Los usuarios finales no reciben esta clave. Minerva mantiene la autenticación y autorización por materia, grupo, plantel e institución.

## Laboratorio de extraccion (DEV)

El frontend accede mediante Minerva API; no publiques estos endpoints directamente.
Ambos requieren `X-AI-Service-Key`:

- `GET /laboratory/documents/prompts`: devuelve `prompts` (`role`, `instructions`,
  `outputContract`), `model` y `maxPdfBytes`. Los valores iniciales viven en
  `DocumentLaboratoryService`; editar las cajas no los modifica.
- `POST /laboratory/documents/extract`: multipart con `file` (PDF) y `request`
  (parte `application/json`):

```json
{
  "sourceType": "STUDY_PLAN",
  "prompts": {
    "role": "Rol y reglas",
    "instructions": "Instrucciones de extraccion",
    "outputContract": "Estructura JSON y ejemplo"
  }
}
```

`sourceType` admite `STUDY_PLAN` (conservar estructura explicita) o
`SOURCE_MATERIAL` (organizar contenido sin conocimientos externos).
Cada capa es obligatoria y admite hasta 12000 caracteres. Se envia una sola
peticion a Ollama con las tres capas y el texto del PDF separado como fuente.

El laboratorio utiliza JSON Schema en `format`, no solo `format: "json"`.
El contrato fijo es `units[] -> name/topics[] -> name/concepts[] -> name`.
Los nombres deben ser no vacios, no se permiten campos adicionales y cada
unidad/tema debe tener hijos. `{"units":[]}` representa ausencia de contenido
didactico identificable. Editar la tercera capa no cambia este contrato.

La respuesta contiene `model`, `durationMs`, `rawOutput`, `tree` y
`validationErrors`. Una salida valida devuelve HTTP 200; una salida malformada
o incompatible devuelve HTTP 422 con `tree: null` y el diagnostico original.
No se recorta ni repara la salida, ni se reintenta automaticamente. Validez
estructural no implica fidelidad al documento.

Se reutilizan los limites de PDF (10 MiB por defecto) y texto extraido
(30000 caracteres por defecto). No hay OCR. No se guardan documentos, prompts
editados ni arboles en disco, PostgreSQL o MinIO. Los endpoints normales de
extraccion, quiz y estadisticas conservan su comportamiento.

El modelo depende del entorno de AI-Service: por ejemplo,
`OLLAMA_MODEL=qwen3:8b` en el servidor y `qwen3:4b` en desarrollo.
