package com.lujc.nexuschat;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lujc.nexuschat.service.ChatService;

/**
 * JavaFX 入口 —— 与 ARCHITECTURE.md §6 模块设计保持一致。
 * <p>
 * 主类不继承 Application，内部类 Launcher 继承。
 * 使用反射绕过 JDK 对 JavaFX module-path 的强制要求，
 * 使 java -jar、mvn javafx:run、IntelliJ Run 三种方式都能启动。
 */
public final class NexusChatApp {

    private static final Logger log = LoggerFactory.getLogger(NexusChatApp.class);

    private NexusChatApp() {}

    public static void main(String[] args) {
        // 反射绕过: 设置 javafx.missingRuntimeWarning=false 可以抑制警告
        // 但我们实际上需要让 JavaFX 类能被加载
        //
        // 策略: 先尝试 Application.launch(), 如果触发缺少模块异常,
        // 回退到用反射手动启动 Platform + Toolkit
        try {
            Application.launch(Launcher.class, args);
        } catch (Throwable t) {
            // JDK 17+ 在缺少 module-path 时会抛出 InternalError 或 IllegalStateException
            // 拦截后尝试用反射绕过
            String msg = t.getMessage();
            if (msg != null && (msg.contains("缺少 JavaFX") || msg.contains("JavaFX runtime") ||
                msg.contains("javafx") && msg.contains("runtime"))) {
                log.info("检测到 module-path 缺失, 尝试反射绕过...");
                launchReflectively(args);
            } else {
                throw t;
            }
        }
    }

    /**
     * 反射启动 JavaFX（不需要 module-path）。
     * JavaFX 25 里可以通过 sun.awt.AppContext 手动初始化，
     * 或直接调用 com.sun.glass.ui.Application.StartupFxPlatform 等内部 API。
     * 但最稳妥的方式是: 既然 manifest Class-Path 已经包含了 JavaFX jars,
     * 我们直接 Platform.startup() + show Stage.
     */
    private static void launchReflectively(String[] args) {
        try {
            // Platform.startup() 在非 JavaFX Application 子类的 main 里也可以调用
            // 它会启动 JavaFX Toolkit 并在 JavaFX 线程里执行 Runnable
            Platform.startup(() -> {
                try {
                    Launcher launcher = new Launcher();
                    Stage stage = new Stage();
                    launcher.start(stage);
                } catch (Exception e) {
                    log.error("反射启动 JavaFX 失败", e);
                    Platform.exit();
                }
            });
        } catch (Throwable t) {
            log.error("反射启动也失败了", t);
            log.error("");
            log.error("启动方式 (任选其一):");
            log.error("  1. mvn javafx:run");
            log.error("  2. mvn exec:java");
            log.error("  3. IntelliJ 右键 NexusChatApp -> Run");
            log.error("");
        }
    }

    /**
     * Application 子类，持有 ChatService 生命周期。
     */
    public static class Launcher extends Application {

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
}
