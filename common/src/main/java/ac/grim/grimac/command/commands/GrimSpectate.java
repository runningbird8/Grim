package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.command.CloudCommandService;
import ac.grim.grimac.command.requirements.PlayerSenderRequirement;
import ac.grim.grimac.platform.api.command.PlayerSelector;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.math.Location;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public class GrimSpectate implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("grim", "grimac")
                        .literal("spectate")
                        .permission("grim.spectate")
                        .required("target", arguments.singlePlayerSelectorParser())
                        .handler(this::handleSpectate)
                        .apply(CloudCommandService.REQUIREMENT_FACTORY.create(PlayerSenderRequirement.INSTANCE))
        );
    }

    private void handleSpectate(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector targetSelectorResults = context.getOrDefault("target", null);
        if (targetSelectorResults == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        Sender target = targetSelectorResults.getSinglePlayer();
        PlatformPlayer targetPlatformPlayer = target == null ? null : target.getPlatformPlayer();

        // Validate every target failure before changing the sender's lifecycle,
        // gamemode, tab visibility, or location.
        if (targetPlatformPlayer == null || !targetPlatformPlayer.isOnline()) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        if (targetPlatformPlayer.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "cannot-run-on-self", "%prefix% &cYou cannot use this command on yourself!"));
            return;
        }

        if (targetPlatformPlayer.isExternalPlayer()) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-this-server", "%prefix% &cThis player isn't on this server!"));
            return;
        }

        PlatformPlayer platformPlayer = sender.getPlatformPlayer();
        if (platformPlayer == null || !platformPlayer.isOnline() || platformPlayer.isExternalPlayer()
                || !GrimAPI.INSTANCE.getSpectateManager().canStartSpectating(sender.getUniqueId())) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "sender-not-found", "%prefix% &cYou cannot use this command right now!"));
            return;
        }

        Location targetLocation = targetPlatformPlayer.getLocation();
        if (targetLocation == null || !targetLocation.isWorldLoaded()) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cThat player's location is not available right now."));
            return;
        }

        switch (GrimAPI.INSTANCE.getSpectateManager().enable(platformPlayer, targetPlatformPlayer, targetLocation)) {
            case STARTED -> sender.sendMessage(MessageUtil.getParsedComponent(sender, "spectate-return", "<click:run_command:/grim stopspectating><hover:show_text:\"/grim stopspectating\">\n%prefix% &fClick here to return to previous location\n</hover></click>"));
            case ALREADY_SPECTATING -> sender.sendMessage(MessageUtil.getParsedComponent(sender, "cannot-spectate-return", "%prefix% &cYou are already spectating. Stop spectating before trying again."));
            case RETURNING -> sender.sendMessage(MessageUtil.getParsedComponent(sender, "cannot-spectate-return", "%prefix% &cYou are still returning. Please wait before spectating again."));
            case RECONNECT_REQUIRED -> sender.sendMessage(MessageUtil.getParsedComponent(sender, "cannot-spectate-return", "%prefix% &cA previous spectate teleport did not settle safely. Reconnect before trying again."));
            case INVALID_SENDER -> sender.sendMessage(MessageUtil.getParsedComponent(sender, "sender-not-found", "%prefix% &cYou cannot use this command right now!"));
            case INVALID_TARGET -> sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cThat player's location is not available right now."));
            case START_FAILED -> sender.sendMessage(MessageUtil.getParsedComponent(sender, "cannot-spectate-return", "%prefix% &cSpectating could not be started. Please try again."));
        }
    }
}
