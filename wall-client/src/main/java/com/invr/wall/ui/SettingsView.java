package com.invr.wall.ui;

import com.invr.wall.board.BoardClient;
import com.invr.wall.board.BoardDto;
import com.invr.wall.board.BoardGoneException;
import com.invr.wall.config.WallConfig;
import com.invr.wall.config.WallConfigStore;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import okhttp3.OkHttpClient;

/**
 * 首次启动 / 运维调起的设置页：粘贴共享链接、可选 RTSP 端口覆盖，
 * 保存前先实际拉一次 board 验证可用性，成功才落盘并进入大屏。
 */
public final class SettingsView extends VBox {

    /** 保存成功回调（FX 线程触发）。 */
    public interface OnSaved {
        void onSaved(WallConfig config);
    }

    private final WallConfigStore store;
    private final OkHttpClient http;
    private final OnSaved onSaved;

    private final TextField linkField = new TextField();
    private final TextField portField = new TextField();
    private final Button saveButton = new Button("连接并进入大屏");
    private final Label errorLabel = new Label();

    public SettingsView(WallConfigStore store, OkHttpClient http, OnSaved onSaved) {
        this.store = store;
        this.http = http;
        this.onSaved = onSaved;

        setId("settingsLayer");
        setAlignment(Pos.CENTER);

        VBox card = new VBox(10);
        card.getStyleClass().add("settings-card");

        Label title = new Label("iNvr 大屏客户端 · 共享看板连接");
        title.getStyleClass().add("settings-title");

        Label linkLabel = new Label("共享链接");
        linkLabel.getStyleClass().add("field-label");
        linkField.setPromptText("粘贴控制台复制的完整共享链接，如 http://192.168.1.10:9080/share/xxxx");

        Label portLabel = new Label("RTSP 端口（可选，留空自动使用看板下发端口，默认 9554）");
        portLabel.getStyleClass().add("field-label");
        portField.setPromptText("例如 9554");

        errorLabel.getStyleClass().add("settings-error");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);

        Label hint = new Label("说明：配置保存在程序数据目录 wall-config.json；之后启动将自动进入全屏大屏。"
                + "快捷键 Ctrl+Shift+S 可重新打开本页。");
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);

        saveButton.getStyleClass().add("btn-primary");
        saveButton.setOnAction(e -> submit());

        card.getChildren().addAll(title, linkLabel, linkField, portLabel, portField,
                errorLabel, saveButton, hint);
        getChildren().add(card);
    }

    /** 重新打开设置页时回填现有配置。 */
    public void fill(WallConfig current) {
        if (current == null) {
            return;
        }
        linkField.setText(current.baseUrl + "/share/" + current.token);
        portField.setText(current.rtspPortOverride == null ? "" : String.valueOf(current.rtspPortOverride));
        errorLabel.setVisible(false);
    }

    private void submit() {
        final WallConfig config;
        try {
            config = WallConfig.parseShareLink(linkField.getText());
            String portText = portField.getText() == null ? "" : portField.getText().trim();
            if (!portText.isEmpty()) {
                int p = Integer.parseInt(portText);
                if (p <= 0 || p > 65535) {
                    throw new IllegalArgumentException("RTSP 端口需在 1-65535 之间");
                }
                config.rtspPortOverride = p;
            }
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
            return;
        }

        saveButton.setDisable(true);
        saveButton.setText("正在校验连接…");
        errorLabel.setVisible(false);

        // 网络校验放后台线程，避免卡 FX 线程
        Thread t = new Thread(() -> {
            try {
                BoardClient probe = new BoardClient(http, config);
                BoardDto board = probe.fetchBoard();
                store.save(config);
                Platform.runLater(() -> {
                    saveButton.setDisable(false);
                    saveButton.setText("连接并进入大屏");
                    onSaved.onSaved(config);
                });
            } catch (BoardGoneException g) {
                Platform.runLater(() -> fail("共享看板已过期、停用或令牌无效（HTTP 410）"));
            } catch (Exception ex) {
                Platform.runLater(() -> fail("连接校验失败：" + ex.getMessage()));
            }
        }, "wall-settings-probe");
        t.setDaemon(true);
        t.start();
    }

    private void fail(String msg) {
        saveButton.setDisable(false);
        saveButton.setText("连接并进入大屏");
        showError(msg);
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
    }
}
