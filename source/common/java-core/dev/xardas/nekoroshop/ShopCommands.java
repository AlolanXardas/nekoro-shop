package dev.xardas.nekoroshop;

import java.util.Collection;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /nekoroshop balance|add|remove|set for operators (and for quests, rewards and other mods that run commands),
 * /balance and /pay for every player when player_commands is on.
 */
public final class ShopCommands {
    private ShopCommands() { }

    private static final int ADD = 0, REMOVE = 1, SET = 2;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(NekoroShop.MOD_ID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("balance")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> show(c, EntityArgument.getPlayer(c, "player")))))
                .then(change("add", ADD))
                .then(change("remove", REMOVE))
                .then(change("set", SET))
                // for trying the market alone: offers from made-up sellers, and removing them again
                .then(Commands.literal("testoffers")
                        .executes(c -> testOffers(c, 40))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
                                .executes(c -> testOffers(c, IntegerArgumentType.getInteger(c, "count"))))
                        .then(Commands.literal("clear")
                                .executes(c -> {
                                    int removed = ShopService.clearTestOffers(c.getSource().getServer());
                                    c.getSource().sendSuccess(() -> Component.translatable(ShopService.key("chat.test_cleared"), removed), true);
                                    return removed;
                                }))));

        if (!ShopConfig.playerCommands) return;
        dispatcher.register(Commands.literal("balance")
                .executes(c -> show(c, c.getSource().getPlayerOrException())));
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(ShopCommands::pay))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> change(String name, int mode) {
        return Commands.literal(name)
                .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                .executes(c -> change(c, EntityArgument.getPlayers(c, "players"), LongArgumentType.getLong(c, "amount"), mode))));
    }

    private static int testOffers(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        int added = ShopService.addTestOffers(c.getSource().getPlayerOrException(), count);
        c.getSource().sendSuccess(() -> Component.translatable(ShopService.key("chat.test_added"), added), true);
        return added;
    }

    private static int show(CommandContext<CommandSourceStack> c, ServerPlayer player) {
        long balance = ShopService.balance(c.getSource().getServer(), player.getUUID());
        boolean self = player == c.getSource().getEntity();
        c.getSource().sendSuccess(() -> self
                ? Component.translatable(ShopService.key("chat.balance"), ShopService.money(balance))
                : Component.translatable(ShopService.key("chat.balance.other"), player.getDisplayName(), ShopService.money(balance)), false);
        return (int) Math.min(Integer.MAX_VALUE, balance);
    }

    private static int change(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players, long amount, int mode) {
        for (ServerPlayer player : players) {
            switch (mode) {
                case ADD -> ShopService.change(player, amount, false);
                case REMOVE -> ShopService.change(player, -amount, false);
                default -> ShopService.change(player, amount, true);
            }
            long now = ShopService.balance(c.getSource().getServer(), player.getUUID());
            c.getSource().sendSuccess(() -> Component.translatable(ShopService.key("chat.changed"), player.getDisplayName(),
                    ShopService.money(now)), true);
        }
        return players.size();
    }

    private static int pay(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer from = c.getSource().getPlayerOrException();
        ServerPlayer to = EntityArgument.getPlayer(c, "player");
        long amount = LongArgumentType.getLong(c, "amount");
        if (from == to) {
            c.getSource().sendFailure(Component.translatable(ShopService.key("error.pay_self")));
            return 0;
        }
        if (!ShopService.pay(from, to, amount)) {
            c.getSource().sendFailure(Component.translatable(ShopService.key("error.money"), ShopConfig.currencyShort));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable(ShopService.key("chat.paid"), ShopService.money(amount), to.getDisplayName()), false);
        to.sendSystemMessage(Component.translatable(ShopService.key("chat.received"), from.getDisplayName(), ShopService.money(amount)));
        return 1;
    }
}
