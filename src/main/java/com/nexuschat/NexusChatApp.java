package com.nexuschat;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nexuschat.service.ChatService;

/**
 * JavaFX 入口 —— 与 ARCHITECTURE.md §6 模块设计保持一致。
 */
public class NexusChatApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(NexusChatApp.class);

    private ChatService chatService;

    @Override
    public void start(Stage stage) throws Exception {
        // 1. 初始化业务层
        chatService = new ChatService();
        chatService.start();

        // 2. 加载 FXML
        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/fxml/Main.fxml"));
        Parent root = loader.load();

        // 3. 注入 ChatService 给 Controller
        MainController controller = loader.getController();
        controller.setChatService(chatService);

        // 4. 配置场景 + 标题
        Scene scene = new Scene(root, 900, 600);
        stage.setTitle("NexusChat v0.1.0");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            log.info("应用关闭");
            chatService.stop();
            Platform.exit();
        });
        stage.show();

        log.info("JavaFX 主窗口已启动");
    }

    @Override
    public void stop() {
        if (chatService != null) chatService.stop();
    }
}
