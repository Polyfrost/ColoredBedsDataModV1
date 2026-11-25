package org.polyfrost.coloredbedsdata;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.hypixel.data.type.GameType;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class ColoredBedsData implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ColoredBedsData");

    private record Mode(String npcName, String id, int teams) {}

    private static final List<Mode> MODES = List.of(
            new Mode("Solo", "BEDWARS_EIGHT_ONE", 8),
            new Mode("4V4V4V4", "BEDWARS_FOUR_FOUR", 4));

    private enum State { IDLE, TO_LOBBY, FIND_NPC, MENU, IN_QUEUE, SCAN }

    private static final int SECOND = 20;
    private static final double UNSURE_SCORE = 0.3;
    private static final Pattern JOIN_BLOCKED = Pattern.compile("blocked from joining a new game for (\\d+) minutes?");

    private final Minecraft mc = Minecraft.getInstance();
    private BedData data;
    private State state = State.IDLE;
    private int modeIndex;
    private int ticks;
    private final Set<String> attempted = new HashSet<>();
    private boolean retried;

    private ClientboundLocationPacket location;

    private String selectedMap;
    private boolean sweptFromFirstPage;
    private String pageBeforeClick;
    private int lastBedCount;
    private int inRangeTicks;
    private int lobbyDelay = 5 * SECOND;
    private int nextLobbySend;
    private long pausedUntil;
    private int stableTicks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(ChatFormatting.stripFormatting(message.getString())));
        HypixelModAPI.getInstance().createHandler(ClientboundLocationPacket.class, packet -> mc.execute(() -> location = packet));
        HypixelModAPI.getInstance().subscribeToEventPacket(ClientboundLocationPacket.class);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("coloredbedsdata")
                .then(literal("start").executes(c -> { start(); return 1; }))
                .then(literal("stop").executes(c -> { stop("Stopped."); return 1; }))
                .then(literal("export").executes(c -> { export(); return 1; }))
                .then(literal("rescan").executes(c -> { rescan(); return 1; }))
                .then(literal("reset").executes(c -> {
                    data = new BedData();
                    data.save();
                    msg("Progress cleared.");
                    return 1;
                }))
                .then(literal("status").executes(c -> {
                    if (data == null) data = BedData.load();
                    msg("State " + state + ", " + data.maps.size() + " maps recorded, mode " + MODES.get(modeIndex).id + ".");
                    return 1;
                }))));
    }

    private void start() {
        if (data == null) data = BedData.load();
        modeIndex = 0;
        attempted.clear();
        retried = false;
        msg("Starting. " + data.maps.size() + " maps already recorded. Needs MVP+ or higher for unlimited map selection.");
        setState(State.TO_LOBBY);
    }

    private void stop(String reason) {
        releaseKeys();
        pausedUntil = 0;
        state = State.IDLE;
        msg(reason);
    }

    private void setState(State next) {
        if (state == State.FIND_NPC) releaseKeys();
        state = next;
        ticks = 0;
        if (next == State.FIND_NPC) {
            inRangeTicks = 0;
            sweptFromFirstPage = false;
            pageBeforeClick = null;
        }
    }

    private void onMessage(String text) {
        if (state == State.IDLE) return;
        if (text.startsWith("Woah there, slow down")) {
            lobbyDelay = Math.min(lobbyDelay * 2, 60 * SECOND);
            nextLobbySend = ticks + lobbyDelay;
            return;
        }
        Matcher blocked = JOIN_BLOCKED.matcher(text);
        if (blocked.find()) {
            int minutes = Integer.parseInt(blocked.group(1));
            pausedUntil = System.currentTimeMillis() + (minutes * 60L + 15) * 1000;
            attempted.remove(MODES.get(modeIndex).id + "/" + selectedMap);
            msg(ChatFormatting.GOLD + "Hypixel blocked joining games for " + minutes + " min, waiting.");
            setState(State.TO_LOBBY);
        }
    }

    private void tick() {
        if (state == State.IDLE || System.currentTimeMillis() < pausedUntil) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        ticks++;

        switch (state) {
            case TO_LOBBY -> {
                if (ticks == 1) nextLobbySend = 1;
                if (ticks == nextLobbySend) {
                    closeScreen();
                    player.connection.sendCommand("lobby bedwars");
                    nextLobbySend = ticks + lobbyDelay;
                }
                if (isLobby()) {
                    lobbyDelay = 5 * SECOND;
                    setState(State.FIND_NPC);
                }
            }
            case FIND_NPC -> {
                if (ticks < SECOND) return;
                Player npc = findNpc(MODES.get(modeIndex).npcName);
                if (npc == null) {
                    releaseKeys();
                    if (ticks == 3 * SECOND) msg(ChatFormatting.GOLD + "Can't find the " + MODES.get(modeIndex).npcName + " NPC, look at it.");
                } else {
                    walkTo(player, npc);
                }
                if (npc != null && player.distanceTo(npc) < 3.5) {
                    EntityHitResult hit = mc.hitResult instanceof EntityHitResult h && h.getEntity() == npc ? h : null;
                    if (hit == null && ++inRangeTicks > SECOND) hit = new EntityHitResult(npc);
                    if (hit != null) {
                        mc.gameMode.interact(player, npc, hit, InteractionHand.MAIN_HAND);
                        setState(State.MENU);
                    }
                } else if (npc == null && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player target && target != player) {
                    mc.gameMode.interact(player, target, hit, InteractionHand.MAIN_HAND);
                    setState(State.MENU);
                } else if (ticks > 40 * SECOND) {
                    setState(State.TO_LOBBY);
                }
            }
            case MENU -> {
                if (ticks % 10 == 0 && handleScreen(mc.gui.screen())) return;
                if (ticks > 6 * SECOND) setState(State.FIND_NPC);
            }
            case IN_QUEUE -> {
                if (location != null && location.getMap().isPresent() && location.getLobbyName().isEmpty()) {
                    lastBedCount = -1;
                    stableTicks = 0;
                    setState(State.SCAN);
                } else if (ticks > 30 * SECOND) {
                    LOGGER.warn("No game found for {}", selectedMap);
                    setState(State.TO_LOBBY);
                }
            }
            case SCAN -> {
                if (ticks % SECOND != 0) return;
                int beds = BedScanner.findBeds(mc.level).size();
                if (beds == lastBedCount) stableTicks += SECOND; else stableTicks = 0;
                lastBedCount = beds;
                int teams = location.getMode().map(ColoredBedsData::teamCount).orElse(MODES.get(modeIndex).teams);
                if ((beds >= teams * 2 && stableTicks >= 2 * SECOND) || stableTicks >= 20 * SECOND || ticks > 90 * SECOND) {
                    record(teams);
                    setState(State.TO_LOBBY);
                }
            }
            default -> {}
        }
    }

    private void record(int teams) {
        String map = location.getMap().orElseThrow();
        String modeId = MODES.get(modeIndex).id;
        List<BedScanner.Result> results = BedScanner.identify(mc.level, teams);
        if (results.size() != teams) {
            msg(ChatFormatting.RED + map + ": found " + results.size() + "/" + teams + " beds, will retry.");
            return;
        }
        List<BedScanner.Bed> beds = new ArrayList<>();
        List<String> unsure = new ArrayList<>();
        for (BedScanner.Result r : results) {
            BlockPos pos = r.blocks().getFirst();
            LOGGER.info("{} bed at {} {} {}: {} ({}%)", map, pos.getX(), pos.getY(), pos.getZ(), r.color(), Math.round(r.score() * 100));
            if (r.score() < UNSURE_SCORE) unsure.add(r.color());
            for (BlockPos b : r.blocks()) beds.add(new BedScanner.Bed(b.getX(), b.getZ(), r.color()));
        }
        String[] sides = BedScanner.sides(beds);
        String[] existing = data.maps.get(map);
        if (teams == 4 && existing != null && Arrays.stream(existing).distinct().count() == 8) {
            data.markDone(modeId, selectedMap);
            data.save();
            msg(ChatFormatting.GRAY + map + ": keeping the 8-team layout.");
            return;
        }
        data.maps.put(map, sides);
        data.scores.put(map, results.stream().mapToDouble(BedScanner.Result::score).min().orElse(0));
        data.markDone(modeId, selectedMap);
        data.save();
        String warn = "";
        if (!unsure.isEmpty()) warn += ChatFormatting.GOLD + " (unsure: " + String.join(", ", unsure) + ")";
        msg(ChatFormatting.GREEN + map + ChatFormatting.GRAY + ": " + String.join(", ", sides) + warn);
    }

    private void rescan() {
        if (state != State.IDLE) {
            msg(ChatFormatting.RED + "Already running, /coloredbedsdata stop first.");
            return;
        }
        if (data == null) data = BedData.load();
        msg("Checking " + data.maps.size() + " maps against upstream...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return BedData.fetchUpstreamMaps();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((upstream, error) -> mc.execute(() -> {
            if (error != null) {
                LOGGER.error("Failed to fetch upstream", error);
                msg(ChatFormatting.RED + "Couldn't fetch upstream: " + error.getMessage());
                return;
            }
            List<String> redo = new ArrayList<>();
            List<String> check = new ArrayList<>();
            for (Map.Entry<String, String[]> e : data.maps.entrySet()) {
                String map = e.getKey();
                String[] sides = e.getValue();
                if (Arrays.equals(sides, upstream.get(map))) continue;
                int teams = MODES.get(0).id.equals(data.modeOf(map)) ? 8 : 4;
                Double score = data.scores.get(map);
                boolean wrong = teams == 8 ? !BedScanner.isTeamOrder(sides) : Arrays.stream(sides).distinct().count() < teams;
                if (wrong || (teams == 4 && (score == null || score < UNSURE_SCORE))) redo.add(map);
                else if (score != null && score < UNSURE_SCORE) check.add(map);
            }
            if (!check.isEmpty()) msg(ChatFormatting.GOLD + "Low confidence, check by hand: " + String.join(", ", check));
            if (redo.isEmpty()) {
                msg("Nothing to rescan.");
                return;
            }
            redo.forEach(data::unmarkDone);
            data.save();
            msg("Rescanning " + redo.size() + ": " + String.join(", ", redo));
            start();
        }));
    }

    private boolean handleScreen(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) return false;
        AbstractContainerMenu menu = container.getMenu();
        int top = menu.slots.size() - 36;
        Mode mode = MODES.get(modeIndex);

        int selector = -1;
        int nextPage = -1;
        int prevPage = -1;
        int target = -1;
        String targetName = null;
        List<String> mapNames = new ArrayList<>();
        for (int i = 0; i < top; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            String name = BedData.cleanName(ChatFormatting.stripFormatting(stack.getHoverName().getString()));
            String lower = name.toLowerCase();
            if (name.startsWith("Map Selector")) selector = i;
            else if (lower.contains("next page")) nextPage = i;
            else if (lower.contains("previous page")) prevPage = i;
            else if (isMapItem(name, stack)) {
                mapNames.add(name);
                if (target == -1 && !data.isDone(mode.id, name) && !attempted.contains(mode.id + "/" + name)) {
                    target = i;
                    targetName = name;
                }
            }
        }

        if (mapNames.isEmpty()) {
            if (selector == -1) return false;
            click(menu, selector);
            ticks = 0;
            return true;
        }

        String page = String.join("|", mapNames);
        if (page.equals(pageBeforeClick) && ticks < 3 * SECOND) return true;
        pageBeforeClick = null;

        if (target != -1) {
            selectedMap = targetName;
            attempted.add(mode.id + "/" + targetName);
            msg("Joining " + targetName + " (" + mode.id + ")");
            click(menu, target);
            location = null;
            setState(State.IN_QUEUE);
            return true;
        }
        if (prevPage == -1) sweptFromFirstPage = true;
        int pageButton = !sweptFromFirstPage ? prevPage : nextPage;
        if (pageButton != -1) {
            pageBeforeClick = page;
            click(menu, pageButton);
            ticks = 0;
            return true;
        }
        nextMode();
        return true;
    }

    private Player findNpc(String name) {
        Entity label = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            Component custom = e.getCustomName();
            if (custom == null) continue;
            String text = ChatFormatting.stripFormatting(custom.getString()).trim().toLowerCase();
            if ((text.equals(name.toLowerCase()) || text.startsWith(name.toLowerCase() + " ["))
                    && (label == null || e.distanceTo(mc.player) < label.distanceTo(mc.player))) label = e;
        }
        if (label == null) return null;
        Player best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Player p) || p == mc.player) continue;
            double dx = p.getX() - label.getX(), dz = p.getZ() - label.getZ();
            if (dx * dx + dz * dz < 4 && (best == null || p.distanceTo(label) < best.distanceTo(label))) best = p;
        }
        return best;
    }

    private void walkTo(LocalPlayer player, Player npc) {
        if (player.getAbilities().flying) {
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, npc.getEyePosition().add(0, -0.3, 0));
        boolean far = player.distanceTo(npc) > 3;
        mc.options.keyUp.setDown(far);
        mc.options.keySprint.setDown(far && player.distanceTo(npc) > 6);
        mc.options.keyJump.setDown(far && player.horizontalCollision);
    }

    private void releaseKeys() {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private static boolean isMapItem(String name, ItemStack stack) {
        if (name.startsWith("Random")) return false;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            String text = ChatFormatting.stripFormatting(line.getString()).toLowerCase();
            if (text.contains("available games") || text.contains("times joined")) return true;
        }
        return false;
    }

    private void nextMode() {
        closeScreen();
        if (++modeIndex < MODES.size()) {
            setState(State.FIND_NPC);
            return;
        }
        if (!retried) {
            retried = true;
            attempted.clear();
            modeIndex = 0;
            setState(State.FIND_NPC);
            return;
        }
        stop("Done, " + data.maps.size() + " maps recorded.");
        export();
    }

    private void export() {
        if (data == null) data = BedData.load();
        BedData snapshot = data;
        CompletableFuture.supplyAsync(() -> {
            try {
                return snapshot.export();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((changes, error) -> mc.execute(() -> {
            if (error != null) {
                LOGGER.error("Export failed", error);
                msg(ChatFormatting.RED + "Export failed: " + error.getMessage());
                return;
            }
            changes.forEach(c -> msg(ChatFormatting.YELLOW + c));
            msg("Wrote " + BedData.OUTPUT + " (" + changes.size() + " changed vs upstream).");
        }));
    }

    private void click(AbstractContainerMenu menu, int slot) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    private void closeScreen() {
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
    }

    private boolean isLobby() {
        return location != null && location.getLobbyName().isPresent() && location.getServerType().orElse(null) == GameType.BEDWARS;
    }

    private static int teamCount(String mode) {
        return mode.contains("EIGHT") ? 8 : mode.contains("FOUR") ? 4 : 2;
    }

    private void msg(String text) {
        LOGGER.info(ChatFormatting.stripFormatting(text));
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(ChatFormatting.AQUA + "[ColoredBedsData] " + ChatFormatting.RESET + text));
    }
}
