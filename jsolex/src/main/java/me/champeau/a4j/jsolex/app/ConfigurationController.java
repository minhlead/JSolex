/*
 * Copyright 2023-2034 the original author or authors.
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
package me.champeau.a4j.jsolex.app;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;

public class ConfigurationController {
    @FXML
    Label spectrumDetectionLabel;
    @FXML
    CheckBox generateDebugImages;
    @FXML
    Slider spectrumDetectionThreshold;

    @FXML
    private void reset() {
        generateDebugImages.setSelected(Configuration.DEFAULT_GENERATE_DEBUG_IMAGES);
        spectrumDetectionThreshold.setValue(Configuration.DEFAULT_SPECTRUM_DETECTION_THRESHOLD);
    }

    public void configure(Configuration config) {
        generateDebugImages.setSelected(config.isDebugImagesGenerationEnabled());
        spectrumDetectionThreshold.setValue(config.getSpectrumDetectionThreshold());
        generateDebugImages.selectedProperty().addListener((observable, oldValue, newValue) -> config.setDebugImagesGenerationEnabled(newValue));
        spectrumDetectionThreshold.valueProperty().addListener((observable, oldValue, newValue) -> config.setSpectrumDetectionThreshold((double) newValue));
        spectrumDetectionLabel.textProperty().bind(spectrumDetectionThreshold.valueProperty().asString("%.2f"));
    }
}
