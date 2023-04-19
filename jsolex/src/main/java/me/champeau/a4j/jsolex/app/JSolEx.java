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
package me.champeau.a4j.jsolex.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import me.champeau.a4j.jsolex.processing.event.ImageGeneratedEvent;
import me.champeau.a4j.jsolex.processing.event.NotificationEvent;
import me.champeau.a4j.jsolex.processing.event.OutputImageDimensionsDeterminedEvent;
import me.champeau.a4j.jsolex.processing.event.PartialReconstructionEvent;
import me.champeau.a4j.jsolex.processing.event.ProcessingEventListener;
import me.champeau.a4j.jsolex.processing.sun.SolexVideoProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class JSolEx extends Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(JSolEx.class);

    private final Configuration config = new Configuration();

    @FXML
    private TextArea console;

    @FXML
    private Menu recentFilesMenu;

    @FXML
    private TabPane mainPane;

    @Override
    public void start(Stage stage) throws Exception {
        var fxmlLoader = new FXMLLoader(getClass().getResource("app.fxml"));
        fxmlLoader.setController(this);

        try {
            var root = (Parent) fxmlLoader.load();
            var scene = new Scene(root, 1024, 768);
            stage.setTitle("JSol'Ex");
            stage.setScene(scene);
            stage.show();
            refreshRecentItemsMenu();
            LogbackConfigurer.configureLogger(console);
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }


    }

    private void refreshRecentItemsMenu() {
        recentFilesMenu.getItems().clear();
        for (Path recentFile : config.getRecentFiles()) {
            var recent = new MenuItem(recentFile.toAbsolutePath().toString());
            recent.setOnAction(e -> doOpen(recentFile.toFile()));
            recentFilesMenu.getItems().add(recent);
        }
    }

    @FXML
    private void config() {

    }

    @FXML
    private void open() {
        var fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SER files", "*.ser"));
        var selectedFile = fileChooser.showOpenDialog(null);
        if (selectedFile != null) {
            doOpen(selectedFile);
        }
    }

    private void doOpen(File selectedFile) {
        config.loaded(selectedFile.toPath());
        Platform.runLater(this::refreshRecentItemsMenu);
        startProcess(selectedFile);

    }

    private void startProcess(File selectedFile) {
        mainPane.getTabs().clear();
        console.textProperty().set("");
        var outputDirName = selectedFile.getName().substring(0, selectedFile.getName().lastIndexOf("."));
        var outputDirectory = new File(selectedFile.getParentFile(), outputDirName);
        try {
            Files.createDirectories(outputDirectory.toPath());
            LOGGER.info("Output directory set to {}", outputDirectory);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var processor = new SolexVideoProcessor(selectedFile, outputDirectory);
        var listener = JFXProcessingEventListener.of(new ProcessingEventListener() {
            private final ImageView imageView = new ImageView();

            @Override
            public void onOutputImageDimensionsDetermined(OutputImageDimensionsDeterminedEvent event) {
                imageView.setPreserveRatio(true);
                int width = event.getWidth();
                int height = event.getHeight();
                imageView.fitWidthProperty().bind(mainPane.widthProperty());
                imageView.setImage(new WritableImage(width, height));
                var colorAdjust = new ColorAdjust();
                colorAdjust.brightnessProperty().setValue(0.2);
                imageView.setEffect(colorAdjust);
                var scrollPane = new ScrollPane();
                scrollPane.setContent(imageView);
                mainPane.getTabs().add(new Tab("Reconstruction", scrollPane));
            }

            @Override
            public void onPartialReconstruction(PartialReconstructionEvent event) {
                WritableImage image = (WritableImage) imageView.getImage();
                var payload = event.getPayload();
                int y = payload.line();
                double[] line = payload.data();
                byte[] rgb = new byte[3 * line.length];
                for (int x = 0; x < line.length; x++) {
                    byte c = (byte) line[x];
                    rgb[3 * x] = c;
                    rgb[3 * x + 1] = c;
                    rgb[3 * x + 2] = c;
                }
                var pixelformat = PixelFormat.getByteRgbInstance();
                image.getPixelWriter().setPixels(0, y, line.length, 1, pixelformat, rgb, 0, 3 * line.length);
            }

            @Override
            public void onImageGenerated(ImageGeneratedEvent event) {
                var tab = new Tab(event.getPayload().title());
                var imageView = new ImageView();
                imageView.setPreserveRatio(true);
                imageView.fitWidthProperty().bind(mainPane.widthProperty());
                imageView.setImage(new Image(event.getPayload().path().toUri().toString()));
                var scrollPane = new ScrollPane();
                scrollPane.setContent(imageView);
                tab.setContent(scrollPane);
                mainPane.getTabs().add(tab);
                mainPane.getSelectionModel().select(tab);
            }

            @Override
            public void onNotification(NotificationEvent e) {
                var alert = new Alert(e.type());
                alert.setTitle(e.title());
                alert.setHeaderText(e.header());
                alert.setContentText(e.message());
                alert.showAndWait();
            }
        });

        processor.addEventListener(listener);
        var task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    processor.process();
                } finally {
                    processor.removeEventListener(listener);
                }
                return null;
            }
        };
        new Thread(task).start();
    }


    @FXML
    private void exit() {
        System.exit(0);
    }

    public static void main(String[] args) {
        launch();
    }
}