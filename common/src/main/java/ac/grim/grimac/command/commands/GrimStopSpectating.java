package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.command.CloudCommandService;
import ac.grim.grimac.command.requirements.PlayerSenderRequirement;
import ac.grim.grimac.manager.SpectateManager;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.List;

public class GrimStopSpectating implements BuildableCommand {

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("grim", "grimac")
                        .literal("stopspectating")
                        .permission("grim.spectate")
                        .optional("here", StringParser.stringParser(), SuggestionProvider.blocking((ctx, in) -> {
                            if (ctx.sender().hasPermission("grim.spectate.stophere")) {
                                return List.of(Suggestion.suggestion("here"));
                            }
                            return List.of(); // No suggestions if no permission
                        }))
                        .handler(this::onStopSpectate)
                        .apply(CloudCommandService.REQUIREMENT_FACTORY.create(PlayerSenderRequirement.INSTANCE))
        );
    }

    public void onStopSpectate(CommandContext<Sender> commandContext) {
        Sender sender = commandContext.sender();
        String string = commandContext.getOrDefault("here", null);
        if (!GrimAPI.INSTANCE.getSpectateManager().isLifecycleActive(sender.getUniqueId())) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "cannot-spectate-return", "%prefix% &cYou can only do this after spectating a player."));
            return;
        }

        if (!GrimAPI.INSTANCE.getSpectateManager().isSpectating(sender.getUniqueId())) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "cannot-spectate-return", "%prefix% &cYou are already returning. Please wait."));
            return;
        }

        if (sender.getPlatformPlayer() == null || !sender.getPlatformPlayer().isOnline()
                || sender.getPlatformPlayer().isExternalPlayer()) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "sender-not-found", "%prefix% &cYou cannot use this command right now!"));
            return;
        }

        boolean teleportBack = string == null || !string.equalsIgnoreCase("here") || !sender.hasPermission("grim.spectate.stophere");
        SpectateManager.StopResult result =
                GrimAPI.INSTANCE.getSpectateManager().disable(sender.getPlatformPlayer(), teleportBack);
        if (result == SpectateManager.StopResult.ALREADY_RETURNING) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "cannot-spectate-return", "%prefix% &cYou are already returning. Please wait."));
        } else if (result == SpectateManager.StopResult.INVALID_PLAYER) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "sender-not-found", "%prefix% &cYou cannot use this command right now!"));
        }
    }
}
