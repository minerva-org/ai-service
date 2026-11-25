package com.minerva.aiservice.service;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.minerva.aiservice.config.AiServiceProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DocumentTextExtractor {
    private static final Logger LOGGER =
            Logger.getLogger(DocumentTextExtractor.class.getName());

    private final PdfTextExtractor pdfTextExtractor;
    private final AiServiceProperties properties;

    public String extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El archivo está vacío.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw unsupportedFormat();
        }

        String normalizedName = filename.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".pdf")) {
            return pdfTextExtractor.extract(file);
        }
        if (normalizedName.endsWith(".pptx")) {
            return extractPowerPoint(file);
        }
        if (!normalizedName.endsWith(".docx")) {
            throw unsupportedFormat();
        }
        if (file.getSize() > properties.limits().maxPdfBytes()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(413),
                    "El documento excede el límite de tamaño configurado.");
        }

        try (XWPFWordExtractor extractor =
                     new XWPFWordExtractor(new XWPFDocument(file.getInputStream()))) {
            String text = extractor.getText().strip();
            if (text.isBlank()) {
                throw new NoExtractableTextException(
                        "El DOCX no contiene texto extraíble.");
            }
            if (text.length() > properties.limits().maxExtractedCharacters()) {
                throw new DocumentTextTooLargeException(
                        "El texto extraído excede el límite configurado.");
            }
            LOGGER.log(Level.INFO,
                    "EXTRACCION_DOCX: caracteresExtraidos={0}",
                    text.length());
            return text;
        } catch (IOException | POIXMLException | NotOfficeXmlFileException exception) {
            throw new InvalidDocumentException(
                    "El DOCX no se pudo leer; verifica que no esté dañado o protegido.",
                    exception);
        }
    }

    public String extractForCurriculumSource(MultipartFile file) {
        String filename = file == null ? null : file.getOriginalFilename();
        if (filename != null
                && filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return pdfTextExtractor.extractForCurriculumSource(file);
        }
        return extract(file);
    }

    private String extractPowerPoint(MultipartFile file) {
        if (file.getSize() > properties.limits().maxPdfBytes()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(413),
                    "El documento excede el límite de tamaño configurado.");
        }

        try (XMLSlideShow presentation =
                     new XMLSlideShow(file.getInputStream())) {
            StringBuilder text = new StringBuilder();
            for (int slideIndex = 0;
                    slideIndex < presentation.getSlides().size();
                    slideIndex++) {
                var slide = presentation.getSlides().get(slideIndex);
                StringBuilder slideText = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String shapeText = textShape.getText();
                        if (shapeText != null && !shapeText.isBlank()) {
                            appendText(slideText, shapeText);
                        }
                    } else if (shape instanceof XSLFTable table) {
                        for (var row : table.getRows()) {
                            for (var cell : row.getCells()) {
                                String cellText = cell.getText();
                                if (cellText != null && !cellText.isBlank()) {
                                    appendText(slideText, cellText);
                                }
                            }
                        }
                    }
                }
                if (!slideText.isEmpty()) {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append("[DIAPOSITIVA ")
                            .append(slideIndex + 1)
                            .append("]\n")
                            .append(slideText);
                }
            }

            String extractedText = text.toString().strip();
            if (extractedText.isBlank()) {
                throw new NoExtractableTextException(
                        "El PPTX no contiene texto extraíble.");
            }
            if (extractedText.length()
                    > properties.limits().maxExtractedCharacters()) {
                throw new DocumentTextTooLargeException(
                        "El texto extraído excede el límite configurado.");
            }
            LOGGER.log(Level.INFO,
                    "EXTRACCION_PPTX: diapositivas={0}, caracteresExtraidos={1}",
                    new Object[]{
                            presentation.getSlides().size(),
                            extractedText.length()});
            return extractedText;
        } catch (IOException | POIXMLException | NotOfficeXmlFileException exception) {
            throw new InvalidDocumentException(
                    "El PPTX no se pudo leer; verifica que no esté dañado o protegido.",
                    exception);
        }
    }

    private static void appendText(StringBuilder target, String value) {
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(value.strip());
    }

    private static ResponseStatusException unsupportedFormat() {
        return new ResponseStatusException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Solo se aceptan documentos PDF, DOCX y PPTX.");
    }

    public static class InvalidDocumentException extends RuntimeException {
        public InvalidDocumentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class NoExtractableTextException extends RuntimeException {
        public NoExtractableTextException(String message) {
            super(message);
        }
    }

    public static class DocumentTextTooLargeException extends RuntimeException {
        public DocumentTextTooLargeException(String message) {
            super(message);
        }
    }
}
