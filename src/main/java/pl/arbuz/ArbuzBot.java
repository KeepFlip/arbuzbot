package pl.arbuz;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ArbuzBot implements ClientModInitializer {

    enum S { IDLE, AIM_CHEST, TAKE, HOME_CMD, HOME_GUI, TP_WAIT, AIM_V, TRADE, AIM_OUT, DEPOSIT, DONE }

    // --- konfiguracja (zapisywana w config/arbuzbot.properties) ---
    BlockPos chest, outChest;
    final List<UUID> vEme = new ArrayList<>(), vXp = new ArrayList<>();
    static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("arbuzbot.properties");

    // --- stan ---
    final Random R = new Random();
    S s = S.IDLE;
    int delay, waited, taken, trades, step, emptyTicks, vIdx, vPos, quota, used, attempts;
    boolean counted;
    String homeName = "Domek #1";
    S afterHome = S.AIM_V;
    Vec3d startPos = Vec3d.ZERO, jitter = Vec3d.ZERO;

    @Override
    public void onInitializeClient() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> {
            d.register(literal("arbuz-skrzynia").executes(c -> setBlock(c, 0)));
            d.register(literal("arbuz-skrzynia-odluz").executes(c -> setBlock(c, 1)));
            d.register(literal("arbuz-villager-eme").executes(c -> setVillager(c, 0)));
            d.register(literal("arbuz-villager-xp").executes(c -> setVillager(c, 1)));
            d.register(literal("arbuz-villager-eme-clear").executes(c -> { vEme.clear(); save(); c.getSource().sendFeedback(Text.literal("[Arbuz] Wyczyszczono liste villagerow eme.")); return 1; }));
            d.register(literal("arbuz-villager-xp-clear").executes(c -> { vXp.clear(); save(); c.getSource().sendFeedback(Text.literal("[Arbuz] Wyczyszczono liste villagerow xp.")); return 1; }));
            d.register(literal("arbuz-status").executes(c -> {
                c.getSource().sendFeedback(Text.literal("[Arbuz] skrzynia: " + (chest != null) + ", odluz: " + (outChest != null)
                        + ", villagerzy eme: " + vEme.size() + ", villagerzy xp: " + vXp.size() + ", stan: " + s));
                return 1;
            }));
            d.register(literal("arbuz-start").executes(c -> {
                if (chest == null || outChest == null || vEme.isEmpty() || vXp.isEmpty()) {
                    c.getSource().sendFeedback(Text.literal("[Arbuz] Najpierw zaznacz: skrzynia, skrzynia-odluz, villager-eme, villager-xp"));
                    return 0;
                }
                c.getSource().getClient().options.pauseOnLostFocus = false; // bot dziala przy zminimalizowanym oknie
                taken = 0; counted = false;
                go(S.AIM_CHEST, 10, 30);
                c.getSource().sendFeedback(Text.literal("[Arbuz] Start. Czekam na 5 stakow arbuzow w skrzynce."));
                return 1;
            }));
            d.register(literal("arbuz-stop").executes(c -> { s = S.IDLE; walk(false); c.getSource().sendFeedback(Text.literal("[Arbuz] Stop.")); return 1; }));
        });
    }

    // ================= komendy zaznaczania =================
    int setBlock(CommandContext<FabricClientCommandSource> c, int which) {
        MinecraftClient mc = c.getSource().getClient();
        if (mc.crosshairTarget instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            if (which == 0) chest = b.getBlockPos(); else outChest = b.getBlockPos();
            save();
            c.getSource().sendFeedback(Text.literal("[Arbuz] Zapisano skrzynke: " + b.getBlockPos().toShortString()));
            return 1;
        }
        c.getSource().sendFeedback(Text.literal("[Arbuz] Patrz na skrzynke!"));
        return 0;
    }

    int setVillager(CommandContext<FabricClientCommandSource> c, int which) {
        MinecraftClient mc = c.getSource().getClient();
        if (mc.crosshairTarget instanceof EntityHitResult e && e.getEntity() instanceof VillagerEntity v) {
            List<UUID> list = which == 0 ? vEme : vXp;
            if (list.contains(v.getUuid())) {
                c.getSource().sendFeedback(Text.literal("[Arbuz] Ten villager jest juz na liscie."));
                return 0;
            }
            list.add(v.getUuid());
            save();
            c.getSource().sendFeedback(Text.literal("[Arbuz] Dodano villagera (" + (which == 0 ? "emeraldy" : "xp") + "), razem: " + list.size()));
            return 1;
        }
        c.getSource().sendFeedback(Text.literal("[Arbuz] Patrz na villagera!"));
        return 0;
    }

    // ================= glowna petla =================
    void tick(MinecraftClient mc) {
        if (s == S.IDLE || s == S.DONE) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) { s = S.IDLE; return; }
        if (p.hurtTime > 0) { fail("Dostales obrazenia - zatrzymuje bota."); return; }
        if (delay > 0) { delay--; return; }
        if (++waited > 800) { fail("Timeout w kroku " + s); return; }

        ScreenHandler h = p.currentScreenHandler;

        switch (s) {
            case AIM_CHEST -> {
                if (openBlock(mc, p, chest)) { counted = false; taken = 0; go(S.TAKE, 6, 14); }
            }
            case TAKE -> {
                if (!(h instanceof GenericContainerScreenHandler g)) {
                    if (waited > 40) go(S.AIM_CHEST, 5, 15);
                    return;
                }
                int chestSlots = g.getRows() * 9;
                if (!counted) {
                    int n = 0;
                    for (int i = 0; i < chestSlots; i++) { ItemStack st = g.getSlot(i).getStack(); if (isFullMelon(st)) n++; }
                    counted = true;
                    if (n < 5) {
                        p.closeHandledScreen();
                        go(S.AIM_CHEST, 600, 1200); // sprawdz ponownie za 30-60 s
                        return;
                    }
                    msg("Jest 5 stakow arbuzow - biore.");
                }
                for (int i = 0; i < chestSlots; i++) {
                    if (isFullMelon(g.getSlot(i).getStack())) {
                        mc.interactionManager.clickSlot(g.syncId, i, 0, SlotActionType.QUICK_MOVE, p);
                        taken++; waited = 0;
                        delay = 3 + R.nextInt(7);
                        if (taken >= 5) {
                            p.closeHandledScreen();
                            homeName = "Domek #1"; afterHome = S.AIM_V; vIdx = 0; vPos = 0; attempts = 0;
                            go(S.HOME_CMD, 10, 30);
                        }
                        return;
                    }
                }
                // brak wiecej arbuzow do wziecia
                p.closeHandledScreen();
                homeName = "Domek #1"; afterHome = S.AIM_V; vIdx = 0; vPos = 0; attempts = 0;
                go(S.HOME_CMD, 10, 30);
            }
            case HOME_CMD -> {
                startPos = p.getPos();
                mc.getNetworkHandler().sendChatCommand("home");
                go(S.HOME_GUI, 10, 25);
            }
            case HOME_GUI -> {
                if (h == p.playerScreenHandler) { if (waited > 100) fail("Menu /home sie nie otworzylo."); return; }
                Pattern pat = Pattern.compile(Pattern.quote(homeName) + "(?!\\d)");
                for (Slot sl : h.slots) {
                    ItemStack st = sl.getStack();
                    if (!st.isEmpty() && pat.matcher(st.getName().getString()).find()) {
                        mc.interactionManager.clickSlot(h.syncId, sl.id, 0, SlotActionType.PICKUP, p);
                        go(S.TP_WAIT, 10, 25);
                        return;
                    }
                }
                if (waited > 100) fail("Nie widze '" + homeName + "' w menu /home.");
            }
            case TP_WAIT -> {
                if (h != p.playerScreenHandler && waited > 60) p.closeHandledScreen();
                if (p.getPos().distanceTo(startPos) > 6) {
                    if (afterHome == S.DONE) { s = S.DONE; msg("Gotowe - jestem na " + homeName + "."); return; }
                    if (h != p.playerScreenHandler) p.closeHandledScreen();
                    go(afterHome, 40, 90); // czekanie na zaladowanie chunkow
                }
            }
            case AIM_V -> {
                List<UUID> grp = group();
                if (vPos >= grp.size()) { nextVillager(); return; }
                Entity v = find(mc, grp.get(vPos));
                if (v == null) {
                    if (waited > 100) { msg("Nie widze villagera #" + (vPos + 1) + " - pomijam."); nextVillager(); }
                    return;
                }
                Box bb = v.getBoundingBox();
                Vec3d eye = p.getEyePos();
                Vec3d near = new Vec3d(MathHelper.clamp(eye.x, bb.minX, bb.maxX), MathHelper.clamp(eye.y, bb.minY, bb.maxY), MathHelper.clamp(eye.z, bb.minZ, bb.maxZ));
                double dist = eye.distanceTo(near);
                if (dist > 7) { walk(false); msg("Villager #" + (vPos + 1) + " za daleko (" + String.format("%.1f", dist) + " m) - pomijam."); nextVillager(); return; }
                walk(dist > 2.5);
                boolean aimed = aim(p, bb.getCenter().add(jitter.multiply(0.3)));
                if (aimed && dist <= 2.9) {
                    walk(false);
                    int have = p.getInventory().count(inputItem());
                    if (have <= 0) { msg("Brak przedmiotow do wymiany - pomijam."); nextVillager(); return; }
                    quota = (int) Math.ceil(have / (double) (grp.size() - vPos)); // podzial po rowno
                    used = 0; trades = 0; step = 0; emptyTicks = 0; attempts++;
                    EntityHitResult hit = new EntityHitResult(v, bb.getCenter());
                    ActionResult r = mc.interactionManager.interactEntityAtLocation(p, v, hit, Hand.MAIN_HAND);
                    if (!r.isAccepted()) mc.interactionManager.interactEntity(p, v, Hand.MAIN_HAND);
                    p.swingHand(Hand.MAIN_HAND);
                    go(S.TRADE, 10, 20);
                }
            }
            case TRADE -> {
                if (!(h instanceof MerchantScreenHandler m)) {
                    if (waited > 60) {
                        if (attempts >= 4) { msg("Villager #" + (vPos + 1) + " sie nie otwiera (brak zawodu / spi?) - pomijam."); nextVillager(); }
                        else go(S.AIM_V, 5, 15);
                    }
                    return;
                }
                TradeOfferList offers = m.getRecipes();
                int idx = findOffer(offers);
                if (idx < 0) { msg("Villager #" + (vPos + 1) + " nie ma odpowiedniej wymiany - pomijam."); p.closeHandledScreen(); nextVillager(); return; }
                TradeOffer o = offers.get(idx);
                ItemStack buy = o.getDisplayedFirstBuyItem();
                boolean done = o.isDisabled() || used >= quota || p.getInventory().count(buy.getItem()) < buy.getCount() || trades >= 600;
                if (done) {
                    msg("Villager #" + (vPos + 1) + " (" + (vIdx == 0 ? "eme" : "xp") + "): " + trades + " wymian.");
                    p.closeHandledScreen();
                    nextVillager();
                    return;
                }
                if (step == 0) {
                    m.setRecipeIndex(idx); m.switchTo(idx);
                    mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(idx));
                    step = 1; emptyTicks = 0; waited = 0;
                    delay = 3 + R.nextInt(5);
                } else {
                    ItemStack out = m.getSlot(2).getStack();
                    if (!out.isEmpty()) {
                        mc.interactionManager.clickSlot(m.syncId, 2, 0, SlotActionType.QUICK_MOVE, p);
                        trades++; used += buy.getCount(); step = 0; waited = 0;
                        delay = 4 + R.nextInt(8);
                    } else {
                        if (++emptyTicks > 8) { step = 0; emptyTicks = 0; }
                        delay = 2;
                    }
                }
            }
            case AIM_OUT -> {
                if (openBlock(mc, p, outChest)) go(S.DEPOSIT, 6, 14);
            }
            case DEPOSIT -> {
                if (!(h instanceof GenericContainerScreenHandler g)) { if (waited > 40) go(S.AIM_OUT, 5, 15); return; }
                int chestSlots = g.getRows() * 9;
                for (int i = chestSlots; i < g.slots.size(); i++) {
                    if (g.getSlot(i).getStack().isOf(Items.EXPERIENCE_BOTTLE)) {
                        mc.interactionManager.clickSlot(g.syncId, i, 0, SlotActionType.QUICK_MOVE, p);
                        waited = 0; delay = 3 + R.nextInt(7);
                        return;
                    }
                }
                p.closeHandledScreen();
                homeName = "Domek #2"; afterHome = S.DONE;
                go(S.HOME_CMD, 15, 40);
            }
            default -> {}
        }
    }

    // ================= pomocnicze =================
    void go(S next, int minD, int maxD) {
        s = next; waited = 0; walk(false);
        delay = minD + R.nextInt(Math.max(1, maxD - minD + 1));
        jitter = new Vec3d((R.nextDouble() - .5) * .5, (R.nextDouble() - .5) * .5, (R.nextDouble() - .5) * .5);
    }

    void fail(String m) { s = S.IDLE; walk(false); msg("BLAD: " + m); }

    void msg(String m) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[Arbuz] " + m), false);
    }

    List<UUID> group() { return vIdx == 0 ? vEme : vXp; }

    Item inputItem() { return vIdx == 0 ? Items.MELON : Items.EMERALD; }

    void walk(boolean on) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) mc.options.forwardKey.setPressed(on);
    }

    /** Przechodzi do nastepnego villagera, potem do drugiej grupy, na koncu do skrzynki z butelkami. */
    void nextVillager() {
        vPos++; attempts = 0;
        if (vPos < group().size()) { go(S.AIM_V, 15, 40); return; }
        if (vIdx == 0) { vIdx = 1; vPos = 0; go(S.AIM_V, 15, 40); return; }
        go(S.AIM_OUT, 15, 40);
    }

    boolean isFullMelon(ItemStack st) { return st.isOf(Items.MELON) && st.getCount() >= st.getMaxCount(); }

    Entity find(MinecraftClient mc, UUID id) {
        if (id == null) return null;
        for (Entity e : mc.world.getEntities()) if (e.getUuid().equals(id)) return e;
        return null;
    }

    int findOffer(TradeOfferList offers) {
        for (int i = 0; i < offers.size(); i++) {
            TradeOffer o = offers.get(i);
            if (vIdx == 0 && o.getSellItem().isOf(Items.EMERALD) && o.getDisplayedFirstBuyItem().isOf(Items.MELON)) return i;
            if (vIdx == 1 && o.getSellItem().isOf(Items.EXPERIENCE_BOTTLE)) return i;
        }
        return -1;
    }

    /** Plynnie celuje w skrzynke i otwiera ja gdy kursor faktycznie na niej jest. */
    boolean openBlock(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos) {
        Vec3d c = Vec3d.ofCenter(pos);
        if (p.getEyePos().distanceTo(c) > 5.5) { fail("Za daleko od skrzynki " + pos.toShortString()); return false; }
        aim(p, c.add(jitter.multiply(0.6)));
        HitResult hr = p.raycast(4.5, 1.0f, false);
        if (hr instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = b.getBlockPos();
            boolean same = bp.equals(pos) || (mc.world.getBlockState(bp).getBlock() == mc.world.getBlockState(pos).getBlock() && bp.getSquaredDistance(pos) <= 2);
            if (same) {
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, b);
                p.swingHand(Hand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    /** Ludzki ruch myszka: latwiejszy start/koniec, losowa predkosc, drobny szum. */
    boolean aim(ClientPlayerEntity p, Vec3d t) {
        Vec3d e = p.getEyePos();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        float ty = (float) (MathHelper.atan2(dz, dx) * 57.29577951) - 90f;
        float tp = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 57.29577951);
        float dyaw = MathHelper.wrapDegrees(ty - p.getYaw());
        float dpit = tp - p.getPitch();
        float k = 0.22f + R.nextFloat() * 0.15f;
        float sy = dyaw * k, sp = dpit * k;
        if (Math.abs(dyaw) > 1.2f && Math.abs(sy) < 0.4f) sy = Math.copySign(0.4f, dyaw);
        if (Math.abs(dpit) > 1.2f && Math.abs(sp) < 0.4f) sp = Math.copySign(0.4f, dpit);
        float n = (Math.abs(dyaw) > 2 || Math.abs(dpit) > 2) ? (R.nextFloat() - .5f) * 0.3f : 0f;
        boolean ok = Math.abs(dyaw) < 2f && Math.abs(dpit) < 2f;
        p.setYaw(p.getYaw() + sy + n);
        p.setPitch(MathHelper.clamp(p.getPitch() + sp + n, -90f, 90f));
        return ok;
    }

    // ================= zapis konfiguracji =================
    void save() {
        try {
            Properties pr = new Properties();
            if (chest != null) pr.setProperty("chest", chest.asLong() + "");
            if (outChest != null) pr.setProperty("outChest", outChest.asLong() + "");
            pr.setProperty("vEme", join(vEme));
            pr.setProperty("vXp", join(vXp));
            try (var w = Files.newBufferedWriter(CFG)) { pr.store(w, "ArbuzBot"); }
        } catch (Exception ignored) {}
    }

    static String join(List<UUID> l) {
        StringBuilder sb = new StringBuilder();
        for (UUID u : l) { if (sb.length() > 0) sb.append(','); sb.append(u); }
        return sb.toString();
    }

    static void parse(String s, List<UUID> out) {
        out.clear();
        for (String part : s.split(",")) {
            if (part.isBlank()) continue;
            try { out.add(UUID.fromString(part.trim())); } catch (Exception ignored) {}
        }
    }

    void load() {
        try {
            if (!Files.exists(CFG)) return;
            Properties pr = new Properties();
            try (var r = Files.newBufferedReader(CFG)) { pr.load(r); }
            if (pr.containsKey("chest")) chest = BlockPos.fromLong(Long.parseLong(pr.getProperty("chest")));
            if (pr.containsKey("outChest")) outChest = BlockPos.fromLong(Long.parseLong(pr.getProperty("outChest")));
            parse(pr.getProperty("vEme", ""), vEme);
            parse(pr.getProperty("vXp", ""), vXp);
        } catch (Exception ignored) {}
    }
}
