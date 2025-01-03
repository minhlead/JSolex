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
package me.champeau.a4j.jsolex.processing.params;

public record SpectroHeliograph(
    String label,
    double totalAngleDegrees,
    int focalLength,
    int collimatorFocalLength,
    int density,
    int order,
    double slitWidthMicrons,
    double slitHeightMillimeters,
    boolean spectrumVFlip
) {
    public static final SpectroHeliograph SOLEX = new SpectroHeliograph("Sol'Ex", 34, 125, 80, 2400, 1, 10, 4.5, false);
    public static final SpectroHeliograph SUNSCAN = new SpectroHeliograph("Sunscan", 34, 100, 75, 2400, 1, 10, 6, true);
    public static final SpectroHeliograph SOLEX_7 = new SpectroHeliograph("Sol'Ex (7μm/6mm slit)", 34, 125, 80, 2400, 1, 7, 6, false);
    public static final SpectroHeliograph SOLEX_10 = new SpectroHeliograph("Sol'Ex (10μm/6mm slit)", 34, 125, 80, 2400, 1, 10, 6, false);
    public static final SpectroHeliograph MLASTRO_SHG_700 = new SpectroHeliograph("MLAstro SHG 700", 34, 75, 75, 2400, 1, 7, 7, false);
    public static final SpectroHeliograph MLASTRO_SHG_400 = new SpectroHeliograph("MLAstro SHG 400", 34, 100, 100, 2400, 1, 7, 7, false);

    public double totalAngleRadians() {
        return Math.toRadians(totalAngleDegrees);
    }

    public SpectroHeliograph withLabel(String label) {
        return new SpectroHeliograph(label, totalAngleDegrees, focalLength, collimatorFocalLength, density, order, slitWidthMicrons, slitHeightMillimeters, spectrumVFlip);
    }

    public SpectroHeliograph withCollimatorFocalLength(int collimatorFocalLength) {
        return new SpectroHeliograph(label, totalAngleDegrees, focalLength, collimatorFocalLength, density, order, slitWidthMicrons, slitHeightMillimeters, spectrumVFlip);
    }

    public SpectroHeliograph withDensity(int density) {
        return new SpectroHeliograph(label, totalAngleDegrees, focalLength, collimatorFocalLength, density, order, slitWidthMicrons, slitHeightMillimeters, spectrumVFlip);
    }

    public SpectroHeliograph withOrder(int order) {
        return new SpectroHeliograph(label, totalAngleDegrees, focalLength, collimatorFocalLength, density, order, slitWidthMicrons, slitHeightMillimeters, spectrumVFlip);
    }

    public SpectroHeliograph withSlitWidthMicrons(double slitWidthMicrons) {
        return new SpectroHeliograph(label, totalAngleDegrees, focalLength, collimatorFocalLength, density, order, slitWidthMicrons, slitHeightMillimeters, spectrumVFlip);
    }

    public SpectroHeliograph withSlitHeightMillimeters(double slideHeightMillimeters) {
        return new SpectroHeliograph(label, totalAngleDegrees, focalLength, collimatorFocalLength, density, order, slitWidthMicrons, slideHeightMillimeters, spectrumVFlip);
    }

    public SpectroHeliograph withCameraFocalLength(int focalLength) {
        return new SpectroHeliograph(label, totalAngleDegrees, focalLength, collimatorFocalLength, density, order, slitWidthMicrons, slitHeightMillimeters, spectrumVFlip);
    }

    public SpectroHeliograph withSpectrumVFlip(boolean spectrumVFlip) {
        return new SpectroHeliograph(label, totalAngleDegrees, focalLength, collimatorFocalLength, density, order, slitWidthMicrons, slitHeightMillimeters, spectrumVFlip);
    }
}
