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
import me.champeau.a4j.jsolex.processing.event.SuggestionEvent;
import me.champeau.a4j.jsolex.processing.sun.Broadcaster;
import me.champeau.a4j.jsolex.processing.sun.crop.Cropper;
import me.champeau.a4j.jsolex.processing.util.ImageWrapper32;
import me.champeau.a4j.jsolex.processing.util.ProcessingException;
import me.champeau.a4j.math.image.Image;
import me.champeau.a4j.math.image.ImageMath;
import me.champeau.a4j.math.regression.Ellipse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.OptionalDouble;

public class GeometryCorrector extends AbstractTask<GeometryCorrector.Result> {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeometryCorrector.class);

    private final Ellipse ellipse;
    private final double correctionAngle;
    private final Double frameRate;
    private final OptionalDouble forcedRatio;
    // This is the sun disk as detected after the initial image correction
    // So that next images use the same geometry
    private final Optional<Ellipse> sunDisk;
    private final float blackPoint;

    public GeometryCorrector(Broadcaster broadcaster,
                             ImageWrapper32 image,
                             Ellipse ellipse,
                             double correctionAngle,
                             Double frameRate,
                             OptionalDouble forcedRatio,
                             Optional<Ellipse> sunDisk,
                             float blackPoint) {
        super(broadcaster, image);
        this.ellipse = ellipse;
        this.correctionAngle = correctionAngle;
        this.frameRate = frameRate;
        this.forcedRatio = forcedRatio;
        this.sunDisk = sunDisk;
        this.blackPoint = blackPoint;
    }

    @Override
    public Result call() throws Exception {
        broadcaster.broadcast(ProgressEvent.of(0, "Correcting geometry"));
        var ratio = ellipse.xyRatio();
        if (forcedRatio.isPresent()) {
            ratio = forcedRatio.getAsDouble();
            LOGGER.info("Overriding X/Y ratio to {}", String.format("%.2f", ratio));
        }
        double sx, sy;
        if (ratio < 1) {
            sx = 1d;
            sy = 1d / ratio;
            if (ratio < 0.98 && frameRate != null && forcedRatio.isEmpty()) {
                double exposureInMillis = 1000d / frameRate;
                broadcaster.broadcast(new SuggestionEvent(
                        "Image is undersampled by a factor of " +
                        String.format("%.2f", ratio) +
                        ". Try to use " + String.format("%.2f ms exposure", exposureInMillis * ratio)
                        + " at acquisition instead of " + String.format("%.2f fps", exposureInMillis))
                );
            }
        } else {
            sx = ratio;
            sy = 1d;
        }
        var atan = Math.atan(correctionAngle);
        var maxDx = height * atan;
        float[] newBuffer = new float[buffer.length];
        for (int y = 0; y < height; y++) {
            var dx = y * atan;
            for (int x = 0; x < width; x++) {
                int nx = (int) (x - Math.max(0, maxDx) + dx);
                if (nx >= 0 && nx < width) {
                    newBuffer[nx + y * width] = buffer[x + y * width];
                }
            }
        }
        var rotated = ImageMath.newInstance().rotateAndScale(new Image(width, height, newBuffer), 0, blackPoint, sx, sy);
        broadcaster.broadcast(ProgressEvent.of(1, "Correcting geometry"));
        var full = new ImageWrapper32(rotated.width(), rotated.height(), rotated.data());
        return crop(rotated, full);
    }

    private Result crop(Image rotated, ImageWrapper32 full) {
        var diskEllipse = sunDisk.orElseGet(() -> {
            EllipseFittingTask.Result fitting;
            try {
                fitting = new EllipseFittingTask(broadcaster, full, .25d, null, null).call();
            } catch (Exception e) {
                throw new ProcessingException(e);
            }
            return fitting.ellipse();
        });
        return new Result(ImageWrapper32.fromImage(Cropper.cropToSquare(rotated, diskEllipse)), diskEllipse);
    }

    public record Result(
            ImageWrapper32 corrected,
            Ellipse disk
    ) {

    }
}
