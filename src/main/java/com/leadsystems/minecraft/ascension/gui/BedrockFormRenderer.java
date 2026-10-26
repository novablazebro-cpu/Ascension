package com.leadsystems.minecraft.ascension.gui;

import com.leadsystems.minecraft.ascension.integration.PlatformDetector;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;

import java.util.List;
import java.util.UUID;

/**
 * Renders a {@link Menu.Screen} as a native Bedrock form (Cumulus via Floodgate): SimpleForm for
 * navigation, ModalForm for yes/no confirmations. Entries become text lines, so Bedrock players see
 * exactly the same information as the chest menu.
 * <p>
 * Form responses arrive on a network thread; they are handed to the main thread before anything
 * happens, and they carry the one-time token captured when the form was built, so a replayed or
 * duplicated response is rejected by {@code MenuService}.
 */
public final class BedrockFormRenderer {

    /** Receives a button press (main thread). */
    @FunctionalInterface
    public interface ClickHandler {
        void clicked(UUID viewer, long token, Menu.Button button);
    }

    private final PlatformDetector platform;
    private final MainThread main;

    public BedrockFormRenderer(PlatformDetector platform, MainThread main) {
        this.platform = platform;
        this.main = main;
    }

    /** Sends the form; false if Floodgate is unavailable or refused it (caller falls back to a chest). */
    public boolean render(Player player, Menu.Screen screen, long token, ClickHandler handler) {
        UUID viewer = player.getUniqueId();
        long now = System.currentTimeMillis();
        String title = formatted(screen.title());
        String content = content(screen, now);
        List<Menu.Button> buttons = screen.buttons();
        Form form;
        if (screen.confirm() && buttons.size() == 2) {
            form = ModalForm.builder()
                    .title(title)
                    .content(content)
                    .button1(formatted(buttons.get(0).label()))
                    .button2(formatted(buttons.get(1).label()))
                    .validResultHandler(response -> {
                        Menu.Button chosen = response.clickedFirst() ? buttons.get(0) : buttons.get(1);
                        main.run(() -> handler.clicked(viewer, token, chosen));
                    })
                    .build();
        } else {
            SimpleForm.Builder builder = SimpleForm.builder().title(title).content(content);
            for (Menu.Button b : buttons) {
                builder.button(buttonText(b, now));
            }
            form = builder.validResultHandler(response -> {
                int id = response.clickedButtonId();
                if (id >= 0 && id < buttons.size()) {
                    Menu.Button chosen = buttons.get(id);
                    main.run(() -> handler.clicked(viewer, token, chosen));
                }
            }).build();
        }
        return platform.sendForm(viewer, form);
    }

    static String buttonText(Menu.Button button) {
        return buttonText(button, System.currentTimeMillis());
    }

    static String buttonText(Menu.Button button, long now) {
        StringBuilder text = new StringBuilder(formatted(button.label()));
        for (int i = 0; i < button.lore().size(); i++) {
            Component line = button.countdowns().containsKey(i)
                    ? button.countdowns().get(i).at(now) : button.lore().get(i);
            text.append("\n").append(formatted(line));
        }
        return text.toString();
    }

    static String content(Menu.Screen screen, long now) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < screen.body().size(); i++) {
            Component line = screen.countdowns().containsKey(i)
                    ? screen.countdowns().get(i).at(now) : screen.body().get(i);
            sb.append(formatted(line)).append("\n");
        }
        for (Menu.Entry e : screen.entries()) {
            sb.append("\n").append(formatted(e.label()));
            for (Component l : e.lore()) {
                sb.append("\n  ").append(formatted(l));
            }
        }
        return sb.toString().trim();
    }

    /** Forms show plain text without Java or Bedrock formatting codes. */
    static String formatted(Component component) {
        return Messages.plain(component);
    }
}
