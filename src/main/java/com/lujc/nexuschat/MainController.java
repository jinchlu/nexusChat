package com.lujc.nexuschat;

import com.lujc.nexuschat.service.ChatService;
import com.lujc.nexuschat.service.FileTransferService;
import com.lujc.nexuschat.storage.ContactRepository;
import com.lujc.nexuschat.storage.DatabaseManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 主窗口 Controller —— Phase 1 文字聊天 + Phase 2 文件/图片传输。
 * 左侧联系人列表 + 右侧消息流（VBox，支持图片缩略图与文件卡片）+ 底部输入区。
 */
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);
    private static final Set<String> IMAGE_EXT =
        Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    @FXML private Label topBar;                 // 顶部：监听端口 + deviceId + 昵称
    @FXML private ListView<String> contactList; // 左侧联系人列表
    @FXML private ScrollPane chatScroll;        // 右侧消息滚动容器
    @FXML private VBox messageBox;              // 消息流
    @FXML private TextArea inputField;
    @FXML private Button sendBtn;
    @FXML private Button attachBtn;
    @FXML private Button addFriendBtn;
    @FXML private HBox transferRow;             // 传输进度行（无传输时隐藏）
    @FXML private ProgressBar transferBar;
    @FXML private Label transferLabel;
    @FXML private Label statusLabel;

    private ChatService chatService;
    private String selectedPeerDeviceId;

    @FXML
    public void initialize() {
        // 用事件过滤器抢先拦截 Enter，交给 handleSend；Shift+Enter 保持 TextArea 默认换行
        inputField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                handleSend();
            }
        });

        sendBtn.setOnAction(e -> handleSend());
        attachBtn.setOnAction(e -> handleAttach());
        addFriendBtn.setOnAction(e -> handleAddFriend());

        contactList.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                int idx = sel.lastIndexOf('(');
                if (idx > 0 && sel.endsWith(")")) {
                    selectedPeerDeviceId = sel.substring(idx + 1, sel.length() - 1);
                }
            }
        });

        // 新消息到达时自动滚到底部
        messageBox.heightProperty().addListener((obs, oldV, newV) -> chatScroll.setVvalue(1.0));
    }

    public void setChatService(ChatService chatService) {
        this.chatService = chatService;

        topBar.setText(String.format("🔌 %s | deviceId=%s | %s",
            chatService.listenPort(), chatService.deviceId(), chatService.nickname()));

        chatService.setOnIncomingText(incoming -> Platform.runLater(() -> {
            appendText(incoming.content(), incoming.senderDeviceId(), false);
            refreshContactList();
        }));

        chatService.setOnStatusUpdate(msg -> Platform.runLater(() -> {
            statusLabel.setText(msg);
            appendSystem(msg);
        }));

        chatService.setOnFileEvent(ev -> Platform.runLater(() -> handleFileEvent(ev)));

        refreshContactList();
    }

    // ── 发送 ──

    private void handleSend() {
        if (selectedPeerDeviceId == null) {
            statusLabel.setText("请先选择一个好友");
            return;
        }
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        chatService.sendText(selectedPeerDeviceId, text);
        appendText(text, chatService.nickname(), true);
        inputField.clear();
    }

    private void handleAttach() {
        if (selectedPeerDeviceId == null) {
            statusLabel.setText("请先选择一个好友");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要发送的文件");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("所有文件", "*.*"),
            new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));

        File file = chooser.showOpenDialog(attachBtn.getScene().getWindow());
        if (file == null) return;

        chatService.sendFile(selectedPeerDeviceId, file.toPath());
        statusLabel.setText("正在发送: " + file.getName());
    }

    private void handleAddFriend() {
        TextInputDialog ipDialog = new TextInputDialog();
        ipDialog.setTitle("添加好友");
        ipDialog.setHeaderText("输入好友 IP 和端口");
        ipDialog.setContentText("格式: IP:端口 （例: 192.168.1.100:15000）");

        ipDialog.showAndWait().ifPresent(input -> {
            String[] parts = input.split(":");
            if (parts.length == 2) {
                try {
                    chatService.addFriend(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException e) {
                    statusLabel.setText("❌ 端口格式错误: " + input);
                }
            } else {
                statusLabel.setText("❌ 格式错误，请用 IP:端口");
            }
        });
    }

    // ── 文件传输事件 ──

    private void handleFileEvent(FileTransferService.FileEvent ev) {
        switch (ev.type()) {
            case STARTED -> {
                transferRow.setVisible(true);
                transferRow.setManaged(true);
                transferBar.setProgress(0);
                transferLabel.setText((ev.outgoing() ? "发送中: " : "接收中: ") + ev.fileName());
            }
            case PROGRESS -> {
                double p = ev.totalBytes() > 0
                    ? (double) ev.transferredBytes() / ev.totalBytes() : 0;
                transferBar.setProgress(Math.min(1.0, p));
                transferLabel.setText(String.format("%s  %d%%  (%s / %s)",
                    ev.fileName(), (int) (p * 100),
                    humanSize(ev.transferredBytes()), humanSize(ev.totalBytes())));
            }
            case COMPLETED -> {
                hideTransferRow();
                appendFile(ev.savedPath(), ev.fileName(), ev.totalBytes(), ev.outgoing());
                statusLabel.setText("传输完成: " + ev.fileName());
            }
            case FAILED -> {
                hideTransferRow();
                appendSystem("❌ 传输失败: " + ev.fileName() + " — " + ev.error());
                statusLabel.setText("传输失败: " + ev.error());
            }
        }
    }

    private void hideTransferRow() {
        transferRow.setVisible(false);
        transferRow.setManaged(false);
        transferBar.setProgress(0);
    }

    // ── 消息渲染 ──

    private void appendText(String text, String who, boolean mine) {
        Label bubble = new Label(text);
        bubble.setWrapText(true);
        bubble.setMaxWidth(520);
        bubble.setStyle(mine
            ? "-fx-background-color: #3498db; -fx-text-fill: white; -fx-padding: 8px 12px; -fx-background-radius: 10;"
            : "-fx-background-color: #ecf0f1; -fx-text-fill: #2c3e50; -fx-padding: 8px 12px; -fx-background-radius: 10;");

        Label sender = new Label(who);
        sender.setStyle("-fx-font-size: 10px; -fx-text-fill: #95a5a6;");

        VBox box = new VBox(2, sender, bubble);
        box.setFillWidth(false);
        addRow(box, mine);
    }

    private void appendFile(Path savedPath, String fileName, long size, boolean mine) {
        if (savedPath == null || !savedPath.toFile().exists()) {
            appendSystem("文件已传输但本地路径不可用: " + fileName);
            return;
        }

        Node card;
        if (isImage(fileName)) {
            ImageView view = new ImageView(new Image(savedPath.toUri().toString(), 320, 0, true, true, true));
            view.setPreserveRatio(true);
            view.setFitWidth(320);
            view.setStyle("-fx-cursor: hand;");
            view.setOnMouseClicked(e -> openFile(savedPath));
            card = view;
        } else {
            Hyperlink link = new Hyperlink("📄 " + fileName + "  (" + humanSize(size) + ")");
            link.setOnAction(e -> openFile(savedPath));
            card = link;
        }

        Label tip = new Label(mine ? "已发送" : "已接收 · 点击打开");
        tip.setStyle("-fx-font-size: 10px; -fx-text-fill: #95a5a6;");

        VBox box = new VBox(2, card, tip);
        box.setFillWidth(false);
        addRow(box, mine);
    }

    private void appendSystem(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");

        HBox row = new HBox(label);
        row.setStyle("-fx-padding: 2px 0;");
        row.setAlignment(javafx.geometry.Pos.CENTER);
        messageBox.getChildren().add(row);
        scrollToBottom();
    }

    private void addRow(Node content, boolean mine) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8);
        row.setStyle("-fx-padding: 2px 0;");
        if (mine) {
            row.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            row.getChildren().addAll(spacer, content);
        } else {
            row.getChildren().addAll(content, spacer);
        }
        messageBox.getChildren().add(row);
        scrollToBottom();
    }

    private void scrollToBottom() {
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    private void openFile(Path path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (Exception e) {
            log.warn("打开文件失败: {}", path, e);
            statusLabel.setText("无法打开文件: " + path.getFileName());
        }
    }

    private static boolean isImage(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        return IMAGE_EXT.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    private void refreshContactList() {
        Platform.runLater(() -> {
            contactList.getItems().clear();
            try {
                // 注意：不能 try-with-resources 关闭 Connection，它由 DatabaseManager 的 ThreadLocal 管理
                var repos = new ContactRepository(DatabaseManager.getConnection());
                for (var c : repos.findAll()) {
                    contactList.getItems().add(
                        (c.online() ? "● " : "○ ") + c.nickname() + " (" + c.deviceId() + ")");
                }
            } catch (Exception e) {
                log.warn("刷新联系人列表失败", e);
            }
        });
    }
}
