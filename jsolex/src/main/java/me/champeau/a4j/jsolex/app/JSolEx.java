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

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import me.champeau.a4j.jsolex.app.jfx.BatchOperations;
import me.champeau.a4j.jsolex.app.jfx.I18N;
import me.champeau.a4j.jsolex.app.jfx.ImageViewer;
import me.champeau.a4j.jsolex.app.jfx.ProcessParamsController;
import me.champeau.a4j.jsolex.app.jfx.SpectralLineDebugger;
import me.champeau.a4j.jsolex.app.jfx.ZoomableImageView;
import me.champeau.a4j.jsolex.processing.event.DebugEvent;
import me.champeau.a4j.jsolex.processing.event.ImageGeneratedEvent;
import me.champeau.a4j.jsolex.processing.event.Notification;
import me.champeau.a4j.jsolex.processing.event.NotificationEvent;
import me.champeau.a4j.jsolex.processing.event.OutputImageDimensionsDeterminedEvent;
import me.champeau.a4j.jsolex.processing.event.PartialReconstructionEvent;
import me.champeau.a4j.jsolex.processing.event.ProcessingDoneEvent;
import me.champeau.a4j.jsolex.processing.event.ProcessingEventListener;
import me.champeau.a4j.jsolex.processing.event.ProcessingStartEvent;
import me.champeau.a4j.jsolex.processing.event.ProgressEvent;
import me.champeau.a4j.jsolex.processing.event.SuggestionEvent;
import me.champeau.a4j.jsolex.processing.params.ProcessParams;
import me.champeau.a4j.jsolex.processing.sun.SolexVideoProcessor;
import me.champeau.a4j.jsolex.processing.util.LoggingSupport;
import me.champeau.a4j.math.VectorApiSupport;
import me.champeau.a4j.ser.SerFileReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static me.champeau.a4j.jsolex.processing.util.LoggingSupport.logError;

public class JSolEx extends Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(JSolEx.class);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Configuration config = new Configuration();

    Stage rootStage;

    @FXML
    private TextArea console;

    @FXML
    private Menu recentFilesMenu;

    @FXML
    private TabPane mainPane;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private Label progressLabel;

    private boolean reconstructionStarted = false;

    private final Map<SuggestionEvent.SuggestionKind, String> suggestions = Collections.synchronizedMap(new LinkedHashMap<>());

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void start(Stage stage) throws Exception {
        this.rootStage = stage;
        var fxmlLoader = I18N.fxmlLoader(getClass(), "app");
        fxmlLoader.setController(this);

        try {
            var root = (Parent) fxmlLoader.load();
            var preferredDimensions = config.getPreferredDimensions();
            Scene rootScene = new Scene(root, preferredDimensions.a(), preferredDimensions.b());
            var pause = new PauseTransition(Duration.seconds(1));
            rootScene.widthProperty().addListener((observable, oldValue, newValue) -> {
                pause.setOnFinished(e -> config.setPreferredWidth(newValue.intValue()));
                pause.playFromStart();
            });
            rootScene.heightProperty().addListener((observable, oldValue, newValue) -> {
                pause.setOnFinished(e -> config.setPreferredHeigth(newValue.intValue()));
                pause.playFromStart();
            });
            hideProgress();
            stage.setTitle("JSol'Ex");
            stage.setScene(rootScene);
            addIcons(stage);
            stage.show();
            refreshRecentItemsMenu();
            LogbackConfigurer.configureLogger(console);
            stage.setOnCloseRequest(e -> executor.shutdownNow());
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    private void addIcons(Stage stage) {
        for (int i = 16; i <= 512; i *= 2) {
            stage.getIcons().add(
                    new Image(getClass().getResourceAsStream("icons/jsolex-" + i + "x" + i + ".png"))
            );
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

    private void hideProgress() {
        progressBar.setProgress(0);
        progressLabel.setText("");
        progressBar.setVisible(false);
    }

    private void showProgress() {
        progressBar.setVisible(true);
    }

    @FXML
    private void open() {
        selectSerFileAndThen(this::doOpen);
    }

    private void selectSerFileAndThen(Consumer<? super File> consumer) {
        var fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SER files", "*.ser"));
        config.findLastOpenDirectory().ifPresent(dir -> fileChooser.setInitialDirectory(dir.toFile()));
        var selectedFile = fileChooser.showOpenDialog(rootStage);
        if (selectedFile != null) {
            LoggingSupport.LOGGER.info("Selected file {}", selectedFile);
            consumer.accept(selectedFile);
        } else {
            LoggingSupport.LOGGER.info("No selected file, processing cancelled.");
        }
    }

    @FXML
    private void showFrameDebugger() {
        selectSerFileAndThen(file -> {
            var fxmlLoader = I18N.fxmlLoader(getClass(), "frame-debugger");
            Object configWindow;
            try {
                configWindow = fxmlLoader.load();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var controller = (SpectralLineDebugger) fxmlLoader.getController();
            var stage = new Stage();
            Scene scene = new Scene((Parent) configWindow, 1024, 400);
            controller.open(file, null, scene);
            stage.setTitle(I18N.string(getClass(), "frame-debugger", "frame.debugger"));
            stage.setScene(scene);
            stage.showAndWait();
        });

    }

    @FXML
    private void about() {
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setResizable(true);
        alert.getDialogPane().setPrefSize(700, 400);
        alert.setTitle(I18N.string(getClass(), "about", "about.title"));
        alert.setHeaderText(I18N.string(getClass(), "about", "about.header"));
        alert.setContentText(I18N.string(getClass(), "about", "about.message"));
        var licenses = new TextArea();
        try {
            licenses.setText(new String(JSolEx.class.getResourceAsStream("/licenses.txt").readAllBytes(), "utf-8"));
        } catch (Exception e) {
            licenses.setText("Cannot find licenses file");
        }
        var scroll = new ScrollPane(licenses);
        scroll.fitToHeightProperty().set(true);
        scroll.fitToWidthProperty().set(true);
        alert.getDialogPane().setExpandableContent(scroll);
        alert.showAndWait();
    }

    private void doOpen(File selectedFile) {
        config.loaded(selectedFile.toPath());
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> logError(e));
        BatchOperations.submit(this::refreshRecentItemsMenu);
        try (var reader = SerFileReader.of(selectedFile)) {
            var controller = createProcessParams(reader);
            var processParams = controller.getProcessParams();
            processParams.ifPresent(params -> startProcess(selectedFile, params));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void startProcess(File selectedFile, ProcessParams params) {
        mainPane.getTabs().clear();
        console.textProperty().set("");
        reconstructionStarted = false;
        var baseName = selectedFile.getName().substring(0, selectedFile.getName().lastIndexOf("."));
        var outputDirectory = selectedFile.getParentFile();
        LOGGER.info("Java runtime version {}", System.getProperty("java.version"));
        LOGGER.info("Vector API support is {} and {}", VectorApiSupport.isPresent() ? "available" : "missing", VectorApiSupport.isEnabled() ? "enabled" : "disabled (enable by setting " + VectorApiSupport.VECTOR_API_ENV_VAR + " environment variable to true)");
        LoggingSupport.LOGGER.info(message("output.dir.set"), outputDirectory);
        var processor = new SolexVideoProcessor(selectedFile,
                outputDirectory.toPath(),
                params
        );
        var listener = new DefaultProcessingEventListener(baseName, params);
        processor.addEventListener(listener);
        var task = new Task<Void>() {
            @Override
            protected Void call() {
                try {
                    processor.process();
                } catch (Exception ex) {
                    LoggingSupport.logError(ex);
                } finally {
                    processor.removeEventListener(listener);
                }
                return null;
            }
        };
        new Thread(task).start();
    }

    private ProcessParamsController createProcessParams(SerFileReader serFileReader) {
        var loader = I18N.fxmlLoader(getClass(), "process-params");
        try {
            var dialog = new Stage();
            dialog.setTitle(I18N.string(getClass(), "process-params", "process.parameters"));
            var content = (Parent) loader.load();
            var controller = (ProcessParamsController) loader.getController();
            var scene = new Scene(content);
            controller.setup(dialog, serFileReader.header());
            dialog.setScene(scene);
            dialog.initOwner(rootStage);
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.showAndWait();
            return controller;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ImageViewer newImageViewer() {
        var fxmlLoader = I18N.fxmlLoader(getClass(), "imageview");
        try {
            var node = (Node) fxmlLoader.load();
            var controller = (ImageViewer) fxmlLoader.getController();
            controller.init(node, mainPane, executor);
            return controller;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @FXML
    private void exit() {
        System.exit(0);
    }

    public static String message(String label) {
        return I18N.string(JSolEx.class, "messages", label);
    }

    public static void main(String[] args) {
        launch();
    }

    private class DefaultProcessingEventListener implements ProcessingEventListener {
        private final Map<Integer, ZoomableImageView> imageViews;
        private final String baseName;
        private final ProcessParams params;
        private long sd;
        private long ed;
        private int width;
        private int height;
        private final Semaphore semaphore;

        public DefaultProcessingEventListener(String baseName, ProcessParams params) {
            this.baseName = baseName;
            this.params = params;
            imageViews = new HashMap<>();
            sd = 0;
            ed = 0;
            width = 0;
            height = 0;
            semaphore = new Semaphore(1);
        }

        @Override
        public void onOutputImageDimensionsDetermined(OutputImageDimensionsDeterminedEvent event) {
            LOGGER.info(message("dimensions.determined"), event.getLabel(), event.getWidth(), event.getHeight());
            if (reconstructionStarted) {
                return;
            }
            reconstructionStarted = true;
            width = event.getWidth();
            height = event.getHeight();
        }

        private ZoomableImageView createImageView(int pixelShift) {
            var imageView = new ZoomableImageView();
            imageView.setPreserveRatio(true);
            imageView.fitWidthProperty().bind(mainPane.widthProperty());
            imageView.setImage(new WritableImage(width, height));
            var colorAdjust = new ColorAdjust();
            colorAdjust.brightnessProperty().setValue(0.2);
            imageView.setEffect(colorAdjust);
            var scrollPane = new ScrollPane();
            scrollPane.setContent(imageView);
            BatchOperations.submit(() -> {
                lock.lock();
                try {
                    String suffix = "";
                    if (pixelShift != 0) {
                        suffix = " (" + pixelShift + ")";
                    }
                    var tab = new Tab(message("reconstruction") + suffix, scrollPane);
                    imageView.setParentTab(tab);
                    mainPane.getTabs().add(tab);
                } finally {
                    lock.unlock();
                }
            });
            return imageView;
        }

        @Override
        public void onPartialReconstruction(PartialReconstructionEvent event) {
            var payload = event.getPayload();
            int y = payload.line();
            if (payload.display()) {
                var imageView = getOrCreateImageView(event);
                WritableImage image = (WritableImage) imageView.getImage();
                double[] line = payload.data();
                byte[] rgb = new byte[3 * line.length];
                for (int x = 0; x < line.length; x++) {
                    int v = (int) Math.round(line[x]);
                    byte c = (byte) (v >> 8);
                    rgb[3 * x] = c;
                    rgb[3 * x + 1] = c;
                    rgb[3 * x + 2] = c;
                }
                var pixelformat = PixelFormat.getByteRgbInstance();
                onProgress(ProgressEvent.of((y + 1d) / height, message("reconstructing")));
                BatchOperations.submit(() -> {
                    if (event.getPayload().pixelShift() == 0) {
                        mainPane.getSelectionModel().select(imageView.getParentTab());
                    }
                    image.getPixelWriter().setPixels(0, y, line.length, 1, pixelformat, rgb, 0, 3 * line.length);
                });
            } else {
                onProgress(ProgressEvent.of((y + 1d) / height, message("reconstructing")));
            }
        }

        private synchronized ZoomableImageView getOrCreateImageView(PartialReconstructionEvent event) {
            return imageViews.computeIfAbsent(event.getPayload().pixelShift(), this::createImageView);
        }

        @Override
        public void onImageGenerated(ImageGeneratedEvent event) {
            BatchOperations.submit(() -> {
                lock.lock();
                try {
                    var tab = new Tab(event.getPayload().title());
                    var viewer = newImageViewer();
                    viewer.fitWidthProperty().bind(mainPane.widthProperty());
                    viewer.setTab(tab);
                    viewer.setup(this,
                            baseName,
                            event.getPayload().image(),
                            event.getPayload().stretchingStrategy(),
                            event.getPayload().path().toFile(),
                            params
                    );
                    var scrollPane = new ScrollPane();
                    scrollPane.setContent(viewer.getRoot());
                    tab.setContent(scrollPane);
                    mainPane.getTabs().add(tab);
                } finally {
                    lock.unlock();
                }
            });
        }

        @Override
        public void onNotification(NotificationEvent e) {
            new Thread(() -> {
                try {
                    if (semaphore.getQueueLength() > 3) {
                        // If there are too many events,
                        // there's probably a big problem
                        // like many exceptons being thrown
                        // so let's not overwhelm the user
                        return;
                    }
                    semaphore.acquire();
                } catch (InterruptedException ex) {
                    logError(ex);
                }
                BatchOperations.submit(() -> {
                    var alert = new Alert(Alert.AlertType.valueOf(e.type().name()));
                    alert.setResizable(true);
                    alert.getDialogPane().setPrefSize(480, 320);
                    alert.setTitle(e.title());
                    alert.setHeaderText(e.header());
                    alert.setContentText(e.message());
                    alert.showAndWait();
                    semaphore.release();
                });
            }).start();
        }

        @Override
        public void onSuggestion(SuggestionEvent e) {
            if (!suggestions.containsKey(e.kind())) {
                suggestions.put(e.kind(), e.getPayload());
            }
        }

        @Override
        public void onProcessingStart(ProcessingStartEvent e) {
            sd = e.getPayload();
        }

        @Override
        public void onProcessingDone(ProcessingDoneEvent e) {
            ed = e.getPayload();
            var duration = java.time.Duration.ofNanos(ed - sd);
            double seconds = duration.toMillis() / 1000d;
            var sb = new StringBuilder();
            if (!suggestions.isEmpty()) {
                sb.append(message("suggestions") + " :\n");
                for (String suggestion : suggestions.values()) {
                    sb.append("    - ").append(suggestion).append("\n");
                }
            }
            var finishedString = String.format(message("finished.in"), seconds);
            onNotification(new NotificationEvent(
                    new Notification(
                            Notification.AlertType.INFORMATION,
                            message("processing.done"),
                            finishedString,
                            sb.toString()
                    )));
            suggestions.clear();
            BatchOperations.submit(() -> {
                progressLabel.setText(finishedString);
                progressLabel.setVisible(true);
            });
        }

        @Override
        public void onProgress(ProgressEvent e) {
            BatchOperations.submitOneOfAKind("progress", () -> {
                if (e.getPayload().progress() == 1) {
                    hideProgress();
                } else {
                    showProgress();
                    progressBar.setProgress(e.getPayload().progress());
                    progressLabel.setText(e.getPayload().task());
                }
            });
        }

        @Override
        public void onDebug(DebugEvent<?> e) {

        }
    }

}
