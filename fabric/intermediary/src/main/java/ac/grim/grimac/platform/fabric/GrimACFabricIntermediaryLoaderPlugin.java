package ac.grim.grimac.platform.fabric;

import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.api.sender.SenderFactory;
import ac.grim.grimac.platform.fabric.manager.FabricItemResetHandler;
import ac.grim.grimac.platform.fabric.command.FabricPlayerSelectorParser;
import ac.grim.grimac.platform.fabric.manager.FabricCloudPlatformCommandArguments;
import ac.grim.grimac.platform.fabric.manager.FabricPermissionRegistrationManager;
import ac.grim.grimac.platform.fabric.player.FabricPlatformPlayerFactory;
import ac.grim.grimac.platform.fabric.scheduler.FabricPlatformScheduler;
import ac.grim.grimac.platform.fabric.sender.AbstractFabricSenderFactory;
import ac.grim.grimac.platform.fabric.sender.FabricIntermediarySenderFactory;
import me.lucko.fabric.api.permissions.v0.Permissions;
import ac.grim.grimac.platform.fabric.utils.FabricIntermediaryPolymerHook;
import ac.grim.grimac.platform.fabric.utils.convert.IFabricConversionUtil;
import ac.grim.grimac.platform.fabric.utils.message.IFabricMessageUtil;
import ac.grim.grimac.utils.lazy.LazyHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public abstract class GrimACFabricIntermediaryLoaderPlugin extends AbstractGrimACFabricLoaderPlugin<
        FabricPlatformPlayerFactory,
        AbstractFabricPlatformServer,
        FabricPlatformScheduler,
        FabricIntermediarySenderFactory,
        FabricItemResetHandler,
        CloudPlatformCommandArguments
        > {
    public static MinecraftServer FABRIC_SERVER;
    public static GrimACFabricIntermediaryLoaderPlugin LOADER;
    private static volatile Method commandSourceStackMethod;

    public GrimACFabricIntermediaryLoaderPlugin(
            LazyHolder<CloudPlatformCommandArguments> commandArguments,
            FabricPlatformPlayerFactory playerFactory,
            AbstractFabricPlatformServer platformServer,
            IFabricMessageUtil fabricMessageUtil,
            IFabricConversionUtil fabricConversionUtil
    ) {
        super(
                LazyHolder.simple(FabricPlatformScheduler::new),
                LazyHolder.simple(FabricIntermediarySenderFactory::new),
                LazyHolder.simple(() -> new FabricItemResetHandler(fabricConversionUtil)),
                commandArguments,
                LazyHolder.simple(() -> new FabricPermissionRegistrationManager(
                        LOADER.getFabricSenderFactory(),
                        name -> {
                            if (AbstractFabricSenderFactory.HAS_PERMISSIONS_API) {
                                Permissions.check(FABRIC_SERVER.createCommandSourceStack(), name);
                            }
                        })),
                playerFactory,
                platformServer,
                fabricMessageUtil,
                fabricConversionUtil
        );
        FabricPlatformServices.configure(
                playerFactory::getPlatformInventory,
                playerFactory::getPlatformEntity,
                player -> FabricIntermediaryPolymerHook.createTranslator((ServerPlayer) player),
                fabricMessageUtil::textLiteral,
                platformServer::getProfileByName,
                fabricConversionUtil
        );
    }

    @Override
    public SenderFactory<CommandSourceStack> getSenderFactory() {
        return senderFactory.get();
    }

    public FabricIntermediarySenderFactory getFabricSenderFactory() {
        return senderFactory.get();
    }

    public static FabricCloudPlatformCommandArguments createCommandArguments() {
        return new FabricCloudPlatformCommandArguments(new FabricPlayerSelectorParser<>(
                selector -> wrapPlayer(selector.single()),
                selector -> selector.inputString()
        ));
    }

    public static CommandSourceStack createCommandSourceStack(ServerPlayer player) {
        try {
            return (CommandSourceStack) commandSourceStackMethod().invoke(player);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access ServerPlayer command source stack method", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("ServerPlayer command source stack method failed", cause);
        }
    }

    public static Sender wrapPlayer(ServerPlayer player) {
        return LOADER.getFabricSenderFactory().wrap(createCommandSourceStack(player));
    }

    private static Method commandSourceStackMethod() {
        Method method = commandSourceStackMethod;
        if (method != null) {
            return method;
        }

        method = findCommandSourceStackMethod();
        commandSourceStackMethod = method;
        return method;
    }

    private static Method findCommandSourceStackMethod() {
        for (String methodName : new String[]{"createCommandSourceStack", "method_64396", "method_5671"}) {
            try {
                Method method = ServerPlayer.class.getMethod(methodName);
                if (CommandSourceStack.class.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }

        throw new IllegalStateException("Could not find ServerPlayer command source stack method");
    }

}
