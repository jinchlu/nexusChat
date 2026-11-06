package com.lujc.nexuschat;

import com.lujc.nexuschat.storage.ContactRepository;
import com.lujc.nexuschat.storage.DatabaseManager;
import com.lujc.nexuschat.service.ChatService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主窗口 Controller —— Phase 1 MVP 简洁版。
 * 左侧联系人列表 + 右侧聊天区域 + 底部输入框 + 顶部状态栏。
 */
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @FXML private Label topBar;               // 顶部信息：监听端口 + deviceId + 昵称
    @FXML private ListView<String> contactList; // 左侧联系人列表
    @FXML private TextArea chatArea;           // 右侧聊天消息区（只读，滚动）
    @FXML private TextField inputField;        // 输入框
    @FXML private Button sendBtn;              // 发送按钮
    @FXML private Button addFriendBtn;         // 加好友按钮
    @FXML private Label statusLabel;           // 底部状态栏

    private ChatService chatService;
    private String selectedPeerDeviceId;       // 当前选中的对端 deviceId

    @FXML
    public void initialize() {
        // Enter 发送
        inputField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                handleSend();
                e.consume();
            }
        });

        sendBtn.setOnAction(e -> handleSend());
        addFriendBtn.setOnAction(e -> handleAddFriend());
        contactList.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                // sel 格式 = "Nickname (deviceId)"
                int idx = sel.lastIndexOf("(");
                if (idx > 0) {
                    selectedPeerDeviceId = sel.substring(idx + 1, sel.length() - 1);
                }
            }
        });
    }

    public void setChatService(ChatService chatService) {
        this.chatService = chatService;

        // 顶部信息
        topBar.setText(String.format("🔌 %s | deviceId=%s | %s",
            chatService.listenPort(), chatService.deviceId(), chatService.nickname()));

        // 注册业务回调
        chatService.setOnIncomingText(incoming -> Platform.runLater(() -> {
            appendChat("[" + incoming.senderDeviceId() + "]: " + incoming.content());
            refreshContactList();
        }));

        chatService.setOnStatusUpdate(msg -> Platform.runLater(() -> {
            statusLabel.setText(msg);
            appendChat("─── " + msg + " ───");
        }));

        refreshContactList();
    }

    // ── UI 事件处理 ──

    private void handleSend() {
        if (selectedPeerDeviceId == null) {
            statusLabel.setText("请先选择一个好友");
            return;
        }
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        chatService.sendText(selectedPeerDeviceId, text);
        appendChat("[我]: " + text);
        inputField.clear();
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
                    String ip = parts[0].trim();
                    int port = Integer.parseInt(parts[1].trim());
                    chatService.addFriend(ip, port);
                } catch (NumberFormatException e) {
                    statusLabel.setText("❌ 端口格式错误: " + input);
                }
            } else {
                statusLabel.setText("❌ 格式错误，请用 IP:端口");
            }
        });
    }

    private void appendChat(String line) {
        chatArea.appendText(line + "\n");
    }

    private void refreshContactList() {
        Platform.runLater(() -> {
            contactList.getItems().clear();
            try (var conn = DatabaseManager.getConnection()) {
                var repos = new ContactRepository(conn);
                for (var c : repos.findAll()) {
                    String onlineIcon = c.online() ? "●" : "○";
                    contactList.getItems().add(
                        onlineIcon + " " + c.nickname() + " (" + c.deviceId() + ")");
                }
            } catch (Exception e) {
                log.warn("刷新联系人列表失败", e);
            }
        });
    }
}
