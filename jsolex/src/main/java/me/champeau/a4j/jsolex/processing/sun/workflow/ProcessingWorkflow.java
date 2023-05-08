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
package me.champeau.a4j.jsolex.processing.sun.workflow;

import me.champeau.a4j.jsolex.app.util.Constants;
import me.champeau.a4j.jsolex.processing.color.ColorCurve;
import me.champeau.a4j.jsolex.processing.event.OutputImageDimensionsDeterminedEvent;
import me.champeau.a4j.jsolex.processing.event.SuggestionEvent;
import me.champeau.a4j.jsolex.processing.params.ProcessParams;
import me.champeau.a4j.jsolex.processing.params.SpectralRay;
import me.champeau.a4j.jsolex.processing.stretching.ArcsinhStretchingStrategy;
import me.champeau.a4j.jsolex.processing.stretching.CutoffStretchingStrategy;
import me.champeau.a4j.jsolex.processing.stretching.LinearStrechingStrategy;
import me.champeau.a4j.jsolex.processing.sun.Broadcaster;
import me.champeau.a4j.jsolex.processing.sun.WorkflowState;
import me.champeau.a4j.jsolex.processing.sun.tasks.CoronagraphTask;
import me.champeau.a4j.jsolex.processing.sun.tasks.EllipseFittingTask;
import me.champeau.a4j.jsolex.processing.sun.tasks.GeometryCorrector;
import me.champeau.a4j.jsolex.processing.sun.tasks.ImageBandingCorrector;
import me.champeau.a4j.jsolex.processing.util.ImageWrapper32;
import me.champeau.a4j.jsolex.processing.util.ParallelExecutor;
import me.champeau.a4j.math.Point2D;
import me.champeau.a4j.math.regression.Ellipse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * This class encapsulates the processing workflow.
 */
public class ProcessingWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingWorkflow.class);
    private static final double CIRCLE_EPSILON = 0.001d;

    private final ParallelExecutor executor;
    private final ProcessParams processParams;
    private final List<WorkflowState> states;
    private final WorkflowState state;
    private final Double fps;
    private final ImageEmitter rawImagesEmitter;
    private final ImageEmitter debugImagesEmitter;
    private final ImageEmitter processedImagesEmitter;
    private final Broadcaster broadcaster;
    private final int currentStep;

    private double tilt;
    private double xyRatio;

    public ProcessingWorkflow(
            Broadcaster broadcaster,
            File rawImagesDirectory,
            File debugImagesDirectory,
            File processedImagesDirectory,
            ParallelExecutor executor,
            List<WorkflowState> states,
            int currentStep,
            ProcessParams processParams,
            Double fps,
            ImageEmitterFactory imageEmitterFactory) {
        this.broadcaster = broadcaster;
        this.executor = executor;
        this.states = states;
        this.state = states.get(currentStep);
        this.processParams = processParams;
        this.fps = fps;
        this.rawImagesEmitter = imageEmitterFactory.newEmitter(broadcaster, executor, rawImagesDirectory);
        this.debugImagesEmitter = imageEmitterFactory.newEmitter(broadcaster, executor, debugImagesDirectory);
        this.processedImagesEmitter = imageEmitterFactory.newEmitter(broadcaster, executor, processedImagesDirectory);
        this.currentStep = currentStep;
    }

    public void start() {
        rawImagesEmitter.newMonoImage(WorkflowStep.RAW_IMAGE, "Raw", "recon", state.image(), CutoffStretchingStrategy.DEFAULT);
        rawImagesEmitter.newMonoImage(WorkflowStep.RAW_IMAGE, "Raw (Linear)", "linear", state.image(), LinearStrechingStrategy.DEFAULT);
        var ellipseFittingTask = executor.submit(new EllipseFittingTask(broadcaster, state.image(), 10d));
        ellipseFittingTask.thenAccept(r -> {
            state.recordResult(WorkflowStep.ELLIPSE_FITTING, r);
            performBandingCorrection(r, state.image()).thenAccept(bandingFixed -> {
                state.recordResult(WorkflowStep.BANDING_CORRECTION, bandingFixed);
                geometryCorrection(r, bandingFixed);
            });
        });

    }

    private void geometryCorrection(EllipseFittingTask.Result result, ImageWrapper32 bandingFixed) {
        var ellipse = result.ellipse();
        var detectedRatio = ellipse.xyRatio();
        this.tilt = processParams.geometryParams().tilt().orElse(ellipse.tiltAngle());
        this.xyRatio = processParams.geometryParams().xyRatio().orElse(detectedRatio);
        LOGGER.info("Detected X/Y ratio: {}", String.format("%.2f", detectedRatio));
        float blackPoint = (float) estimateBlackPoint(bandingFixed, ellipse) * 1.2f;
        var tiltDegrees = ellipse.tiltAngle() / Math.PI * 180;
        var geometryParams = processParams.geometryParams();
        boolean isTiltReliable = ellipse.isAlmostCircle(CIRCLE_EPSILON);
        var tiltString = String.format("%.2f", tiltDegrees);
        if (Math.abs(tiltDegrees) > 1 && isTiltReliable && geometryParams.tilt().isEmpty()) {
            broadcaster.broadcast(new SuggestionEvent("Tilt angle is " + tiltString + ". You should try to reduce it to less than 1°"));
        }
        var correctionAngle = isTiltReliable ? -ellipse.tiltAngle() : 0d;
        LOGGER.info("Tilt angle: {}°", tiltString);
        if (geometryParams.tilt().isPresent()) {
            correctionAngle = -geometryParams.tilt().getAsDouble() / 180d * Math.PI;
            LOGGER.info("Overriding tilt angle to {}°", String.format("%.2f", geometryParams.tilt().getAsDouble()));
        }
        if (!isTiltReliable) {
            LOGGER.info("Will not apply rotation correction as sun disk is almost a circle (and therefore tilt angle is not reliable)");
            this.tilt = 0;
        }
        var diskEllipse = Optional.<Ellipse>empty();
        if (currentStep > 0) {
            diskEllipse = states.get(0).findResult(WorkflowStep.GEOMETRY_CORRECTION).map(r -> ((GeometryCorrector.Result)r).disk());
        }
        executor.submit(new GeometryCorrector(broadcaster, bandingFixed, ellipse, correctionAngle, fps, geometryParams.xyRatio(), diskEllipse)).thenAccept(g -> {
            var disk = g.disk();
            var geometryFixed = g.corrected();
            state.recordResult(WorkflowStep.GEOMETRY_CORRECTION, geometryFixed);
            broadcaster.broadcast(OutputImageDimensionsDeterminedEvent.of("geometry corrected", geometryFixed.width(), geometryFixed.height()));
            processedImagesEmitter.newMonoImage(WorkflowStep.GEOMETRY_CORRECTION, "Disk", "disk", geometryFixed, LinearStrechingStrategy.DEFAULT);
            if (state.isEnabled(WorkflowStep.EDGE_DETECTION_IMAGE)) {
                executor.submit(() -> produceEdgeDetectionImage(result, geometryFixed));
            }
            if (state.isEnabled(WorkflowStep.STRECHED_IMAGE)) {
                executor.submit(() -> produceStretchedImage(blackPoint, geometryFixed));
            }
            if (state.isEnabled(WorkflowStep.COLORIZED_IMAGE)) {
                executor.submit(() -> produceColorizedImage(blackPoint, geometryFixed, processParams));
            }
            if (state.isEnabled(WorkflowStep.CORONAGRAPH)) {
                executor.submit(() -> produceCoronagraph(blackPoint, geometryFixed));
            }
            if (state.isEnabled(WorkflowStep.DOPPLER_IMAGE)) {
                produceDopplerImage(blackPoint);
            }
        });
    }

    private void produceDopplerImage(float blackPoint) {
        if (processParams.spectrumParams().ray() != SpectralRay.H_ALPHA) {
            return;
        }
        executor.submit(() -> {
            var dopplerShift = processParams.spectrumParams().dopplerShift();
            var first = states.stream().filter(s -> s.pixelShift() == -dopplerShift).findFirst();
            var second = states.stream().filter(s -> s.pixelShift() == dopplerShift).findFirst();
            first.ifPresent(s1 -> second.ifPresent(s2 -> {
                s1.findResult(WorkflowStep.GEOMETRY_CORRECTION).ifPresent(i1 -> s2.findResult(WorkflowStep.GEOMETRY_CORRECTION).ifPresent(i2 -> {
                    var grey1 = (ImageWrapper32) i1;
                    var grey2 = (ImageWrapper32) i2;
                    var width = grey1.width();
                    var height = grey1.height();
                    processedImagesEmitter.newColorImage(WorkflowStep.DOPPLER_IMAGE,
                            "Doppler",
                            "doppler",
                            new ArcsinhStretchingStrategy(blackPoint, 1, 20),
                            width,
                            height,
                            () -> toDopplerImage(width, height, grey1, grey2));
                }));
            }));
        });
    }

    private static float[][] toDopplerImage(int width, int height, ImageWrapper32 grey1, ImageWrapper32 grey2) {
        float[] r = new float[width * height];
        float[] g = new float[width * height];
        float[] b = new float[width * height];
        var d1 = grey1.data();
        var d2 = grey2.data();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                var idx = x + y * width;
                r[idx] = d1[idx];
                g[idx] = (d1[idx] + d2[idx]) / 2;
                b[idx] = d2[idx];
            }
        }
        return new float[][]{r, g, b};
    }

    private void produceColorizedImage(float blackPoint, ImageWrapper32 corrected, ProcessParams params) {
        CutoffStretchingStrategy.DEFAULT.stretch(corrected.data());
        params.spectrumParams().ray().getColorCurve().ifPresent(curve ->
                processedImagesEmitter.newColorImage(WorkflowStep.COLORIZED_IMAGE, "Colorized (" + curve.ray() + ")", "colorized", corrected, new ArcsinhStretchingStrategy(blackPoint, 10, 200), mono -> convertToRGB(curve, mono))
        );
    }

    private static float[][] convertToRGB(ColorCurve curve, float[] mono) {
        LinearStrechingStrategy.DEFAULT.stretch(mono);
        float[] r = new float[mono.length];
        float[] g = new float[mono.length];
        float[] b = new float[mono.length];
        for (int i = 0; i < mono.length; i++) {
            var rgb = curve.toRGB(mono[i]);
            r[i] = (float) rgb.a();
            g[i] = (float) rgb.b();
            b[i] = (float) rgb.c();
        }
        return new float[][]{r, g, b};
    }

    private CompletableFuture<ImageWrapper32> performBandingCorrection(EllipseFittingTask.Result r, ImageWrapper32 geometryFixed) {
        return executor.submit(new ImageBandingCorrector(broadcaster, geometryFixed, r.ellipse(), processParams.bandingCorrectionParams()));
    }

    private Future<Void> produceStretchedImage(float blackPoint, ImageWrapper32 geometryFixed) {
        return processedImagesEmitter.newMonoImage(WorkflowStep.STRECHED_IMAGE, "Stretched", "streched", geometryFixed, new ArcsinhStretchingStrategy(blackPoint, 10, 100));
    }

    private void produceEdgeDetectionImage(EllipseFittingTask.Result result, ImageWrapper32 geometryFixed) {
        if (processParams.debugParams().generateDebugImages()) {
            debugImagesEmitter.newMonoImage(WorkflowStep.EDGE_DETECTION_IMAGE, "Edge detection", "edge-detection", geometryFixed, LinearStrechingStrategy.DEFAULT, debugImage -> {
                var samples = result.samples();
                Arrays.fill(debugImage, 0f);
                fill(result.ellipse(), debugImage, geometryFixed.width(), (int) Constants.MAX_PIXEL_VALUE / 4);
                for (Point2D sample : samples) {
                    var x = sample.x();
                    var y = sample.y();
                    debugImage[(int) Math.round(x + y * geometryFixed.width())] = Constants.MAX_PIXEL_VALUE;
                }
            });
        }
    }

    private void produceCoronagraph(float blackPoint, ImageWrapper32 geometryFixed) {
        executor.submit(new EllipseFittingTask(broadcaster, geometryFixed, 6d))
                .thenAccept(fitting -> executor.submit(new CoronagraphTask(broadcaster, geometryFixed, fitting, blackPoint)).thenAccept(coronagraph -> {
                            processedImagesEmitter.newMonoImage(WorkflowStep.CORONAGRAPH, "Coronagraph", "protus", coronagraph, LinearStrechingStrategy.DEFAULT);
                            var data = geometryFixed.data();
                            var copy = new float[data.length];
                            System.arraycopy(data, 0, copy, 0, data.length);
                            LinearStrechingStrategy.DEFAULT.stretch(copy);
                            var width = geometryFixed.width();
                            var height = geometryFixed.height();
                            var ellipse = fitting.ellipse();
                            float[] mix = new float[data.length];
                            var coronaData = coronagraph.data();
                            var filtered = new float[coronaData.length];
                            System.arraycopy(coronaData, 0, filtered, 0, filtered.length);
                            prefilter(fitting.ellipse(), filtered, width, height);
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < width; x++) {
                                    var index = x + y * width;
                                    if (ellipse.isWithin(x, y)) {
                                        mix[index] = copy[index];
                                    } else {
                                        mix[index] = filtered[index];
                                    }
                                }
                            }
                            var mixedImage = new ImageWrapper32(width, height, mix);
                            var colorCurve = processParams.spectrumParams().ray().getColorCurve();
                            if (colorCurve.isPresent()) {
                                var curve = colorCurve.get();
                                processedImagesEmitter.newColorImage(WorkflowStep.COLORIZED_IMAGE, "Mix", "mix", mixedImage, new ArcsinhStretchingStrategy(blackPoint, 10, 200), mono -> convertToRGB(curve, mono));
                            } else {
                                processedImagesEmitter.newMonoImage(WorkflowStep.CORONAGRAPH, "Mix", "mix", mixedImage, LinearStrechingStrategy.DEFAULT);
                            }
                        })
                );
    }

    /**
     * The farther we are from the center, and outside of the sun
     * disk, the most likely it's either a protuberance or an artifact.
     * This reduces artifacts by decreasing pixel values for pixels
     * far from the limb.
     *
     * @param ellipse the circle representing the sun disk
     */
    private void prefilter(Ellipse ellipse, float[] filtered, int width, int height) {
        var center = ellipse.center();
        var cx = center.a();
        var cy = center.b();
        var radius = ellipse.semiAxis().a();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!ellipse.isWithin(x, y)) {
                    var dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                    // compute distance to circle
                    var scale = Math.pow(Math.log(0.99 + dist / radius) / Math.log(2), 10);
                    filtered[x + y * width] /= scale;
                }
            }
        }
    }


    public double getTilt() {
        return tilt;
    }

    public double getXyRatio() {
        return xyRatio;
    }

    private static double estimateBlackPoint(ImageWrapper32 image, Ellipse ellipse) {
        var width = image.width();
        var height = image.height();
        var buffer = image.data();
        double blackEstimate = Double.MAX_VALUE;
        int cpt = 0;
        var cx = width / 2d;
        var cy = height / 2d;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (!ellipse.isWithin(x, y)) {
                    var v = buffer[x + y * width];
                    if (v > 0) {
                        var offcenter = 2 * Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (width + height);
                        blackEstimate = blackEstimate + (offcenter * v - blackEstimate) / (++cpt);
                    }
                }
            }
        }
        LOGGER.info("Black estimate {}", blackEstimate);
        return blackEstimate;
    }


    private static void fill(Ellipse ellipse, float[] image, int width, int color) {
        int height = image.length / width;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (ellipse.isWithin(x, y)) {
                    image[x + y * width] = color;
                }
            }
        }
    }

}
