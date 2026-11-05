package com.invr.wall.ui;

import com.invr.wall.media.PlayerState;
import com.invr.wall.media.RtspPlayer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 大屏槽位：画面 ImageView + 右下角名称条 + 状态占位叠放。
 *
 * <p>播放器回调来自抓取线程，所有 UI 变更统一切 FX 线程。画面采用拉伸铺满
 * （与 Web 端 object-fit:fill 行为一致），由 GridPane 单元格决定实际显示尺寸。</p>
 */
public final class CellPane extends StackPane {

    private final String slotKey;
    private String did;
    private final ImageView imageView = new ImageView();
    private final HBox bar = new HBox(8);
    private final Label tagLabel = new Label();
    private final Label nameLabel = new Label();
    private final StackPane placeholder = new StackPane();
    private final Label phIcon = new Label();
    private final Label phText = new Label();

    private RtspPlayer player;

    private final RtspPlayer.Listener playerListener = new RtspPlayer.Listener() {
        @Override
        public void onStateChanged(RtspPlayer p, PlayerState state) {
            Platform.runLater(() -> applyPlayerState(state));
        }

        @Override
        public void onImageChanged(RtspPlayer p) {
            Platform.runLater(() -> imageView.setImage(p.image()));
        }
    };

    public CellPane(String slotKey, String tagText) {
        this.slotKey = slotKey;
        getStyleClass().add("cell");

        imageView.getStyleClass().add("cell-image");
        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        imageView.fitWidthProperty().bind(widthProperty());
        imageView.fitHeightProperty().bind(heightProperty());

        tagLabel.getStyleClass().add("tag");
        tagLabel.setText(tagText);
        nameLabel.getStyleClass().add("name");
        bar.getStyleClass().add("cell-bar");
        bar.getChildren().addAll(tagLabel, nameLabel);
        // HBox 默认最大尺寸无限大，会被 StackPane 拉伸铺满整格（半透明背景看起来像蒙版）；
        // 钉死为按内容自适应，BOTTOM_RIGHT 对齐才能真正生效
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(bar, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(bar, new Insets(0, 8, 8, 8));

        phIcon.getStyleClass().add("ph-icon");
        phText.getStyleClass().add("ph-text");
        VBox phBox = new VBox(8, phIcon, phText);
        phBox.setAlignment(Pos.CENTER);
        placeholder.getStyleClass().add("placeholder");
        placeholder.getChildren().add(phBox);

        getChildren().addAll(imageView, placeholder, bar);
        showPlaceholder("loading", "正在连接…");
    }

    public String slotKey() {
        return slotKey;
    }

    public String did() {
        return did;
    }

    public RtspPlayer player() {
        return player;
    }

    public void setCameraName(String name) {
        nameLabel.setText(name == null ? "" : name);
    }

    /** 手动刷新时的即时反馈：立刻显示刷新遮罩，播放器出画后由状态回调自动覆盖。 */
    public void flashRefreshing() {
        imageView.setVisible(false);
        showPlaceholder("loading", "正在刷新连接…");
    }

    public void setEventTag(boolean event) {
        tagLabel.getStyleClass().removeAll("event");
        if (event) {
            tagLabel.getStyleClass().add("event");
            tagLabel.setText("事件");
        }
    }

    /** 绑定播放器并立即按其当前状态渲染。 */
    public void bindPlayer(String did, RtspPlayer p) {
        unbindPlayer();
        this.did = did;
        this.player = p;
        p.setListener(playerListener);
        imageView.setImage(p.image());
        applyPlayerState(p.state());
    }

    public void unbindPlayer() {
        // 播放器生命周期归 PlayerPool / 全屏管理器，这里只解除 UI 引用
        this.player = null;
        this.did = null;
        imageView.setImage(null);
    }

    /** 空置槽位（did=null）。 */
    public void showEmpty() {
        unbindPlayer();
        imageView.setVisible(false);
        showPlaceholder("empty", "空置");
    }

    /** 离线槽位：保留 did 关联以便上线后恢复，但不持有播放器。 */
    public void showOffline(String did) {
        this.did = did;
        this.player = null;
        imageView.setImage(null);
        imageView.setVisible(false);
        showPlaceholder("offline", "设备离线");
    }

    private void applyPlayerState(PlayerState state) {
        switch (state) {
            case PLAYING -> {
                placeholder.setVisible(false);
                placeholder.setManaged(false);
                imageView.setVisible(true);
            }
            case OFFLINE -> {
                imageView.setVisible(false);
                showPlaceholder("offline", "设备离线");
            }
            case ERROR -> {
                imageView.setVisible(false);
                showPlaceholder("error", "取流失败，等待恢复…");
            }
            case RECONNECTING -> {
                imageView.setVisible(player != null && player.image() != null);
                showPlaceholder("loading", "取流中断，重连中…");
            }
            default -> {
                imageView.setVisible(false);
                showPlaceholder("loading", "正在连接…");
            }
        }
    }

    private void showPlaceholder(String kind, String text) {
        placeholder.getStyleClass().removeAll("offline", "error", "empty", "loading");
        placeholder.getStyleClass().add(kind);
        phIcon.setText(switch (kind) {
            case "offline" -> "OFF";
            case "error" -> "!";
            case "empty" -> "○";
            default -> "…";
        });
        phText.setText(text);
        placeholder.setVisible(true);
        placeholder.setManaged(true);
    }

    RtspPlayer.Listener playerListener() {
        return playerListener;
    }
}
