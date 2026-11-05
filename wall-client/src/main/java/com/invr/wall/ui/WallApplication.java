package com.invr.wall.ui;

import com.invr.wall.config.WallConfig;
import com.invr.wall.config.WallConfigStore;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX 入口：装配全屏舞台与各图层（网格 / 事件全屏 / 状态条 / 启动失效层 / 设置层），
 * 负责配置加载、控制器生命周期与运维快捷键。
 */
public class WallApplication extends Application {

    private static final String CSS_PATH = "/wall.css";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // SSE 长连接不允许读超时；普通 board 请求很快，不受影响
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private final WallConfigStore configStore = new WallConfigStore();

    private Stage stage;
    private GridPane boardGrid;
    private StackPane fullscreenLayer;
    private Label notice;
    private VBox bootLayer;
    private Label bootTitle;
    private Label bootSub;
    private SettingsView settingsView;

    private WallController controller;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        StackPane root = buildRoot();
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());

        primaryStage.setTitle("iNvr 大屏客户端");
        primaryStage.setScene(scene);
        // 窗口化最小尺寸 480x270（16:9 下限）；网格始终按百分比分割，窗口任意大小都等比铺满
        primaryStage.setMinWidth(480);
        primaryStage.setMinHeight(270);
        primaryStage.setFullScreen(true);
        primaryStage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        primaryStage.show();

        registerShortcuts(scene);

        WallConfig saved = configStore.load();
        if (saved != null) {
            launchWall(saved);
        } else {
            openSettings(null);
        }
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    private StackPane buildRoot() {
        boardGrid = new GridPane();
        boardGrid.setId("boardGrid");
        // 允许网格被拉伸填满整个根区域：全屏/窗口化、任意窗口尺寸下都由百分比轨道按比例分割
        boardGrid.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane.setAlignment(boardGrid, Pos.CENTER);

        fullscreenLayer = new StackPane();
        fullscreenLayer.setId("fullscreenLayer");
        fullscreenLayer.setVisible(false);
        fullscreenLayer.setManaged(false);

        notice = new Label();
        notice.setId("notice");
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.setVisible(false);
        notice.setManaged(false);
        StackPane.setAlignment(notice, Pos.TOP_CENTER);

        bootTitle = new Label("正在加载…");
        bootTitle.getStyleClass().add("boot-title");
        bootSub = new Label("");
        bootSub.getStyleClass().add("boot-sub");
        bootLayer = new VBox(14, bootTitle, bootSub);
        bootLayer.setId("bootLayer");
        bootLayer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(bootLayer, Priority.ALWAYS);

        settingsView = new SettingsView(configStore, http, this::onConfigSaved);
        settingsView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        settingsView.setVisible(false);
        settingsView.setManaged(false);

        StackPane root = new StackPane(boardGrid, fullscreenLayer, notice, bootLayer, settingsView);
        root.setStyle("-fx-background-color: #000000;");
        return root;
    }

    private void registerShortcuts(Scene scene) {
        KeyCodeCombination openSettings = new KeyCodeCombination(KeyCode.S,
                KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
        scene.setOnKeyPressed(e -> {
            if (openSettings.match(e)) {
                openSettings(configStore.load());
            } else if (e.getCode() == KeyCode.F11) {
                stage.setFullScreen(!stage.isFullScreen());
            } else if (e.getCode() == KeyCode.F5 && controller != null) {
                controller.refreshConnections();
            }
        });
    }

    private void onConfigSaved(WallConfig config) {
        settingsView.setVisible(false);
        settingsView.setManaged(false);
        launchWall(config);
    }

    private void openSettings(WallConfig current) {
        settingsView.fill(current);
        settingsView.setVisible(true);
        settingsView.setManaged(true);
        settingsView.toFront();
    }

    private void launchWall(WallConfig config) {
        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        // 清理上一个看板的残留图层
        boardGrid.getChildren().clear();
        fullscreenLayer.getChildren().clear();
        fullscreenLayer.setVisible(false);
        fullscreenLayer.setManaged(false);
        notice.setVisible(false);
        notice.setManaged(false);

        WallController.WallViews views = new WallController.WallViews(
                boardGrid, fullscreenLayer, notice, bootLayer, bootTitle, bootSub);
        controller = new WallController(config, http, views);
        controller.start();
        boardGrid.toFront();
    }
}
