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
package me.champeau.a4j.jsolex.processing.sun.tasks;

import me.champeau.a4j.jsolex.processing.event.ProgressEvent;
import me.champeau.a4j.jsolex.processing.expr.impl.Crop;
import me.champeau.a4j.jsolex.processing.params.ProcessParams;
import me.champeau.a4j.jsolex.processing.sun.Broadcaster;
import me.champeau.a4j.jsolex.processing.sun.WorkflowState;
import me.champeau.a4j.jsolex.processing.sun.detection.RedshiftArea;
import me.champeau.a4j.jsolex.processing.sun.detection.Redshifts;
import me.champeau.a4j.jsolex.processing.sun.workflow.ImageEmitter;
import me.champeau.a4j.jsolex.processing.sun.workflow.TransformationHistory;
import me.champeau.a4j.jsolex.processing.util.FileBackedImage;
import me.champeau.a4j.jsolex.processing.util.ImageWrapper;
import me.champeau.a4j.jsolex.processing.util.ImageWrapper32;
import me.champeau.a4j.jsolex.processing.util.MutableMap;
import me.champeau.a4j.math.Point2D;
import me.champeau.a4j.math.image.Image;
import me.champeau.a4j.math.image.ImageMath;
import me.champeau.a4j.math.regression.Ellipse;
import me.champeau.a4j.math.regression.EllipseRegression;
import me.champeau.a4j.ser.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static me.champeau.a4j.jsolex.processing.util.Constants.message;

public class GeometryCorrector extends AbstractTask<GeometryCorrector.Result> {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeometryCorrector.class);

    private final Ellipse ellipse;
    private final Double forcedTilt;
    private final Double xyRatio;
    private final Double frameRate;
    private final float blackPoint;
    private final ProcessParams processParams;
    private final ImageEmitter imageEmitter;
    private final WorkflowState state;
    private final Header header;

    public GeometryCorrector(Broadcaster broadcaster,
                             Supplier<ImageWrapper32> image,
                             Ellipse ellipse,
                             Double forcedTilt,
                             Double frameRate,
                             Double xyRatio,
                             float blackPoint,
                             ProcessParams processParams,
                             ImageEmitter imageEmitter,
                             WorkflowState state,
                             Header header) {
        super(broadcaster, image);
        this.ellipse = ellipse;
        this.forcedTilt = forcedTilt;
        this.frameRate = frameRate;
        this.xyRatio = xyRatio;
        this.blackPoint = blackPoint;
        this.processParams = processParams;
        this.imageEmitter = imageEmitter;
        this.state = state;
        this.header = header;
    }

    @Override
    public Result doCall() throws Exception {
        broadcaster.broadcast(ProgressEvent.of(0, message("correcting.geometry")));
        var theta = forcedTilt == null ? ellipse.rotationAngle() : forcedTilt;
        var m = Math.tan(-theta);
        var semiAxis = ellipse.semiAxis();
        var a = semiAxis.a();
        var b = semiAxis.b();
        var cos = Math.cos(theta);
        var sin = Math.sin(theta);
        var shear = (m * cos * a * a + sin * b * b) / (b * b * cos - a * a * m * sin);
        LOGGER.debug("a = {}, b={}, theta={}", a, b, theta);
        var maxDx = height * shear;
        var shift = maxDx < 0 ? maxDx : 0;
        float[] newBuffer;
        int extendedWidth;
        var buffer = getBuffer();
        extendedWidth = width + (int) Math.ceil(Math.abs(maxDx));
        newBuffer = new float[height * extendedWidth];
        for (int y = 0; y < height; y++) {
            var dx = y * shear;
            for (int x = 0; x < width; x++) {
                var nx = x - shift + dx;
                var x1 = (int) Math.floor(nx);
                var x2 = x1 + 1;
                var factor = nx - x1;
                if (x1 >= 0 && x2 < extendedWidth) {
                    newBuffer[x1 + y * extendedWidth] += (1 - factor) * buffer[x + y * width];
                    newBuffer[x2 + y * extendedWidth] += factor * buffer[x + y * width];
                }
                // reduce transform artifacts by filling with same border color
                if (x == 0) {
                    for (int k = 0; k < nx; k++) {
                        newBuffer[k + y * extendedWidth] = buffer[x + y * width];
                    }
                } else if (x == width - 1) {
                    for (int k = (int) nx; k < extendedWidth; k++) {
                        newBuffer[k + y * extendedWidth] = buffer[x + y * width];
                    }
                }
            }
        }
        double sx;
        double sy = Math.abs((a * b * Math.sqrt((a * a * m * m + b * b) / (a * a * sin * sin + b * b * cos * cos)) / (b * b * cos - a * a * m * sin)));
        if (xyRatio == null) {
            LOGGER.info(message("detected.xy.ratio"), String.format("%.2f", sy));
        }
        if (xyRatio != null) {
            sy = xyRatio;
        }
        if (sy < 1 || !processParams.geometryParams().isDisallowDownsampling()) {
            sx = 1 / sy;
            sy = 1.0d;
        } else {
            sx = 1.0d;
        }
        var imageMath = ImageMath.newInstance();
        var rescaled = imageMath.rotateAndScale(new Image(extendedWidth, height, newBuffer), 0, blackPoint, sx, sy);
        double finalSy = sy;
        var circle = computeCorrectedCircle(shear, shift, sx, finalSy);
        var metadata = new HashMap<>(getMetadata());
        metadata.put(Ellipse.class, circle);
        Redshifts redshifts = (Redshifts) metadata.get(Redshifts.class);
        if (redshifts != null) {
            // correct redshifts
            redshifts = new Redshifts(redshifts.redshifts().stream()
                .map(r -> {
                    var x1 = r.x1() + shift - r.y1() * shear;
                    var y1 = r.y1();
                    var x2 = r.x2() + shift - r.y2() * shear;
                    var y2 = r.y2();
                    var maxX = r.maxX() + shift - r.maxY() * shear;
                    var maxY = r.maxY();
                    return new RedshiftArea(r.id(), r.pixelShift(), r.relPixelShift(), r.kmPerSec(), (int) (x1 * sx), (int) (y1 * finalSy), (int) (x2 *sx), (int) (y2 * finalSy), (int) (maxX * sx), (int) (maxY * finalSy));
                })
                .toList());
            metadata.put(Redshifts.class, redshifts);
        }
        var corrected = ImageWrapper32.fromImage(rescaled, metadata);
        TransformationHistory.recordTransform(corrected, message("geometry.correction"));
        var autocropMode = processParams.geometryParams().autocropMode();
        if (autocropMode != null) {
            var cropping = new Crop(MutableMap.of(Ellipse.class, ellipse), broadcaster);
            corrected = switch (autocropMode) {
                case RADIUS_1_1 -> (ImageWrapper32) cropping.autocrop2(List.of(corrected, 1.1));
                case RADIUS_1_2 -> (ImageWrapper32) cropping.autocrop2(List.of(corrected, 1.2));
                case RADIUS_1_5 -> (ImageWrapper32) cropping.autocrop2(List.of(corrected, 1.5));
                case SOURCE_WIDTH -> {
                    var targetWidth = header == null ? width : header.geometry().width();
                    var center = circle.center();
                    var halfWidth = targetWidth / 2d;
                    var cx = center.a();
                    var cy = center.b();
                    if (cx - halfWidth >= 0 && (cy - halfWidth >= 0) && (cx + halfWidth <= targetWidth) && (cy + halfWidth <= targetWidth)) {
                        yield (ImageWrapper32) cropping.cropToRect(List.of(corrected, targetWidth, targetWidth, circle));
                    } else {
                        LOGGER.warn(message("destructive.cannot.crop"));
                        yield corrected;
                    }
                }
                default -> corrected;
            };
            TransformationHistory.recordTransform(corrected, message("autocrop"));
        }
        broadcaster.broadcast(ProgressEvent.of(1, message("correcting.geometry")));
        var wrapped = FileBackedImage.wrap(corrected);
        return new Result(wrapped, wrapped, ellipse, corrected.findMetadata(Ellipse.class).orElse(circle), blackPoint);
    }

    /**
     * Performs new ellipse regression, where sample points are taken from the
     * original ellipse, but corrected in the same way as we correct the image,
     * that is to say with a shear transform + x/y ratio correction.
     *
     * @param shear the shear value
     * @param shift pixel shifting to avoid negative number overflow
     * @param sx the x correction ratio
     * @param sy the y correction ratio
     * @return a circle, if detected, or null.
     */
    private Ellipse computeCorrectedCircle(double shear, double shift, double sx, double sy) {
        var newSamples = IntStream.range(0, 32)
            .mapToDouble(i -> i * Math.PI / 16)
            .mapToObj(ellipse::toCartesian)
            .map(p -> {
                var newX = (p.x() - shift + p.y() * shear);
                return new Point2D(newX * sx, p.y() * sy);
            })
            .toList();
        Ellipse correctedEllipse = null;
        try {
            correctedEllipse = new EllipseRegression(newSamples).solve();
        } catch (Exception ex) {
            // ignore
        }
        return correctedEllipse;
    }

    public record Result(
        ImageWrapper corrected,
        ImageWrapper enhanced,
        Ellipse originalEllipse,
        Ellipse correctedCircle,
        float blackpoint
    ) {
        public Result withEnhanced(ImageWrapper32 enhanced) {
            return new Result(FileBackedImage.wrap(corrected), FileBackedImage.wrap(enhanced), originalEllipse, correctedCircle, blackpoint);
        }
    }
}
