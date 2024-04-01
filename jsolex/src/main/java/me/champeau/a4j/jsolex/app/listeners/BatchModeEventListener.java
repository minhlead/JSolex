/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.champeau.a4j.jsolex.app.listeners;

import me.champeau.a4j.jsolex.app.JSolEx;
import me.champeau.a4j.jsolex.app.jfx.BatchItem;
import me.champeau.a4j.jsolex.app.jfx.BatchOperations;
import me.champeau.a4j.jsolex.app.jfx.RotationCorrector;
import me.champeau.a4j.jsolex.app.script.JSolExScriptExecutor;
import me.champeau.a4j.jsolex.processing.event.FileGeneratedEvent;
import me.champeau.a4j.jsolex.processing.event.GeneratedImage;
import me.champeau.a4j.jsolex.processing.event.ImageGeneratedEvent;
import me.champeau.a4j.jsolex.processing.event.Notification;
import me.champeau.a4j.jsolex.processing.event.NotificationEvent;
import me.champeau.a4j.jsolex.processing.event.OutputImageDimensionsDeterminedEvent;
import me.champeau.a4j.jsolex.processing.event.PartialReconstructionEvent;
import me.champeau.a4j.jsolex.processing.event.ProcessingDoneEvent;
import me.champeau.a4j.jsolex.processing.event.ProcessingEventListener;
import me.champeau.a4j.jsolex.processing.event.ProcessingStartEvent;
import me.champeau.a4j.jsolex.processing.event.ScriptExecutionResultEvent;
import me.champeau.a4j.jsolex.processing.event.VideoMetadataEvent;
import me.champeau.a4j.jsolex.processing.expr.DefaultImageScriptExecutor;
import me.champeau.a4j.jsolex.processing.expr.ImageMathScriptExecutor;
import me.champeau.a4j.jsolex.processing.expr.ImageMathScriptResult;
import me.champeau.a4j.jsolex.processing.file.FileNamingStrategy;
import me.champeau.a4j.jsolex.processing.params.ProcessParams;
import me.champeau.a4j.jsolex.processing.params.RotationKind;
import me.champeau.a4j.jsolex.processing.stretching.RangeExpansionStrategy;
import me.champeau.a4j.jsolex.processing.sun.workflow.GeneratedImageKind;
import me.champeau.a4j.jsolex.processing.util.Constants;
import me.champeau.a4j.jsolex.processing.util.ImageSaver;
import me.champeau.a4j.jsolex.processing.util.ImageWrapper;
import me.champeau.a4j.jsolex.processing.util.MutableMap;
import me.champeau.a4j.jsolex.processing.util.ProcessingException;
import me.champeau.a4j.jsolex.processing.util.SolarParametersUtils;
import me.champeau.a4j.ser.Header;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static me.champeau.a4j.jsolex.processing.util.LoggingSupport.LOGGER;

public class BatchModeEventListener implements ProcessingEventListener, ImageMathScriptExecutor {

    private final JSolExInterface owner;
    private final SingleModeProcessingEventListener delegate;
    private final ProcessParams processParams;
    private final BatchItem item;
    private final AtomicInteger completed;
    private final double totalItems;
    private final File outputDirectory;
    private final LocalDateTime processingDate;
    private final int sequenceNumber;
    private DefaultImageScriptExecutor batchScriptExecutor;

    private Header header;
    private final Map<String, List<ImageWrapper>> imagesByLabel;

    public BatchModeEventListener(JSolExInterface owner,
                                  SingleModeProcessingEventListener delegate,
                                  int sequenceNumber,
                                  BatchProcessingContext context,
                                  ProcessParams processParams) {
        this.owner = owner;
        this.delegate = delegate;
        this.processParams = processParams;
        this.completed = context.progress();
        this.outputDirectory = context.outputDirectory();
        this.processingDate = context.processingDate();
        this.imagesByLabel = context.imagesByLabel();
        this.item = context.items().stream().filter(batchItem -> batchItem.id() == sequenceNumber).findFirst().get();
        this.totalItems = context.items().size();
        this.sequenceNumber = sequenceNumber;
    }

    @Override
    public void onImageGenerated(ImageGeneratedEvent event) {
        var payload = event.getPayload();
        var image = payload.image();
        var kind = payload.kind();
        var target = payload.path().toFile();

        var img = image;
        double correction = 0;
        if (!kind.shouldRotateImage()) {
            correction = image.findMetadata(RotationKind.class).orElseGet(() -> processParams.geometryParams().rotation()).angle();
            if (processParams.geometryParams().isAutocorrectAngleP()) {
                correction += SolarParametersUtils.computeSolarParams(processParams.observationDetails().date().toLocalDateTime()).p();
            }
        }
        if (correction != 0) {
            img = RotationCorrector.rotate(img, correction);
        }
        new ImageSaver(RangeExpansionStrategy.DEFAULT, processParams).save(img, target);
        item.generatedFiles().add(target);
    }

    @Override
    public void onPartialReconstruction(PartialReconstructionEvent event) {
        var payload = event.getPayload();
        item.reconstructionProgress().setValue(payload.line() / (double) payload.totalLines());
    }

    @Override
    public void onOutputImageDimensionsDetermined(OutputImageDimensionsDeterminedEvent event) {
        LOGGER.info(JSolEx.message("dimensions.determined"), event.getLabel(), event.getWidth(), event.getHeight());
        item.reconstructionProgress().setValue(1.0);
    }

    @Override
    public void onVideoMetadataAvailable(VideoMetadataEvent event) {
        this.header = event.getPayload();
    }

    @Override
    public void onProcessingStart(ProcessingStartEvent e) {
        item.status().set(JSolEx.message("batch.started"));
        updateProgressStatus(false);
    }

    @Override
    public void onProcessingDone(ProcessingDoneEvent e) {
        updateProgressStatus(true);
        maybeWriteLogs();
        if (item.status().get().equals(JSolEx.message("batch.error"))) {
            return;
        }
        item.status().set(JSolEx.message("batch.ok"));
        if (completed.get() == totalItems) {
            executeBatchScriptExpressions();
            BatchOperations.submit(() -> {
                owner.updateProgress(1.0, String.format(JSolEx.message("batch.finished")));
            });
        }
    }

    private void executeBatchScriptExpressions() {
        var scriptFiles = processParams.requestedImages().mathImages().scriptFiles();
        if (scriptFiles.isEmpty()) {
            return;
        }
        batchScriptExecutor = new JSolExScriptExecutor(
            idx -> {
                throw new IllegalStateException("Cannot call img() in batch outputs. Use variables to store images instead");
            },
            MutableMap.of(),
            null
        );
        for (Map.Entry<String, List<ImageWrapper>> entry : imagesByLabel.entrySet()) {
            batchScriptExecutor.putVariable(entry.getKey(), entry.getValue());
        }
        var namingStrategy = createNamingStrategy();
        boolean initial = true;
        for (File scriptFile : scriptFiles) {
            if (initial) {
                owner.prepareForScriptExecution(this, processParams);
                initial = false;
            }
            executeBatchScript(namingStrategy, scriptFile);
        }
    }


    private void executeBatchScript(FileNamingStrategy namingStrategy, File scriptFile) {
        BatchOperations.submit(() -> {
            owner.updateProgress(0, String.format(JSolEx.message("executing.script"), scriptFile));
        });
        ImageMathScriptResult result;
        try {
            result = batchScriptExecutor.execute(scriptFile.toPath(), ImageMathScriptExecutor.SectionKind.BATCH);
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
        try {
            processScriptErrors(result);
            renderBatchOutputs(namingStrategy, result);
        } finally {
            BatchOperations.submit(() -> owner.updateProgress(1, String.format(JSolEx.message("executing.script"), scriptFile)));
        }
    }

    private void renderBatchOutputs(FileNamingStrategy namingStrategy, ImageMathScriptResult result) {
        result.imagesByLabel().entrySet().stream().parallel().forEach(entry -> {
            var name = namingStrategy.render(0, Constants.TYPE_PROCESSED, entry.getKey(), "batch");
            var outputFile = new File(outputDirectory, name);
            delegate.onImageGenerated(new ImageGeneratedEvent(
                new GeneratedImage(GeneratedImageKind.IMAGE_MATH, entry.getKey(), outputFile.toPath(), entry.getValue())
            ));

        });
        result.filesByLabel().entrySet().stream().parallel().forEach(entry -> {
            var name = namingStrategy.render(0, Constants.TYPE_PROCESSED, entry.getKey(), "batch");
            try {
                var fileName = entry.getValue().toFile().getName();
                var ext = fileName.substring(fileName.lastIndexOf("."));
                var targetPath = new File(outputDirectory, name + ext).toPath();
                Files.move(entry.getValue(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                delegate.onFileGenerated(FileGeneratedEvent.of(entry.getKey(), targetPath));
            } catch (IOException e) {
                throw new ProcessingException(e);
            }
        });
    }

    @Override
    public void onScriptExecutionResult(ScriptExecutionResultEvent e) {
        synchronized (imagesByLabel) {
            var images = e.getPayload().imagesByLabel();
            for (Map.Entry<String, ImageWrapper> entry : images.entrySet()) {
                imagesByLabel.computeIfAbsent(entry.getKey(), unused -> new ArrayList<>())
                    .add(entry.getValue());
            }
        }
    }

    private void maybeWriteLogs() {
        if (item.log().length() > 0 && header != null) {
            var namingStrategy = createNamingStrategy();
            var fileName = item.file().getName();
            var logFileName = namingStrategy.render(sequenceNumber, "log", "notifications", fileName.substring(0, fileName.lastIndexOf("."))) + ".txt";
            try {
                var logFilePath = outputDirectory.toPath().resolve(logFileName);
                Files.writeString(logFilePath, item.log().toString(), Charset.defaultCharset());
                item.generatedFiles().add(logFilePath.toFile());
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private FileNamingStrategy createNamingStrategy() {
        return new FileNamingStrategy(
            processParams.extraParams().fileNamePattern(),
            processParams.extraParams().datetimeFormat(),
            processParams.extraParams().dateFormat(),
            processingDate,
            header
        );
    }

    private void updateProgressStatus(boolean increment) {
        var done = increment ? completed.incrementAndGet() : completed.get();
        BatchOperations.submitOneOfAKind("progress", () -> {
            var prog = done / totalItems;
            if (completed.get() == (int) totalItems) {
                owner.showProgress();
            } else {
                owner.showProgress();
                owner.updateProgress(prog, String.format(JSolEx.message("batch.progress"), done, (int) totalItems));
            }
        });
    }

    @Override
    public void onNotification(NotificationEvent e) {
        synchronized (item) {
            item.log()
                .append(e.type()).append(": ")
                .append(e.title()).append(" ")
                .append(e.header()).append(" ")
                .append(e.message()).append("\n");
        }
        if (e.type() == Notification.AlertType.ERROR) {
            item.status().set(JSolEx.message("batch.error"));
        }
    }

    @Override
    public ImageMathScriptResult execute(String script, SectionKind kind) {
        var result = batchScriptExecutor.execute(script, SectionKind.BATCH);
        processScriptErrors(result);
        return result;
    }

    private void processScriptErrors(ImageMathScriptResult result) {
        var invalidExpressions = result.invalidExpressions();
        var errorCount = invalidExpressions.size();
        if (errorCount > 0) {
            String message = invalidExpressions.stream()
                .map(invalidExpression -> "Expression '" + invalidExpression.label() + "' (" + invalidExpression.expression() + ") : " + invalidExpression.error().getMessage())
                .collect(Collectors.joining(System.lineSeparator()));
            String details = invalidExpressions.stream()
                .map(invalidExpression -> {
                    var sb = new StringWriter();
                    invalidExpression.error().printStackTrace(new java.io.PrintWriter(sb));
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
            delegate.onNotification(new NotificationEvent(new Notification(
                Notification.AlertType.ERROR,
                JSolEx.message("error.processing.script"),
                JSolEx.message("script.errors." + (errorCount == 1 ? "single" : "many")),
                message
            )));
        }
    }
}
