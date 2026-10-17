package pl.arbuz;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
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
import net.minecraft.world.RaycastContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ArbuzBot implements ClientModInitializer {

    enum S { IDLE, FLEE, AIM_CHEST, TAKE, HOME_CMD, HOME_GUI, TP_WAIT, AIM_V, TRADE, AIM_OUT, DEPOSIT, DONE }

    // --- konfiguracja (zapisywana w config/arbuzbot.properties) ---
    BlockPos chest, outChest;
    final List<UUID> vEme = new ArrayList<>(), vXp = new ArrayList<>();
    static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("arbuzbot.properties");

    // --- stan ---
    static final double MAX_WALK = 30.0; // max dystans chodzenia (m)
    static final int MELON_TARGET = 320;  // 5 stakow arbuzow
    int takenTotal, needTotal;
    int passMade, idlePasses;              // ile wymian w tym przejsciu / ile przejsc z rzedu bez zadnej wymiany
    final int[] lastBuyG = {1, 1};         // ile sztuk kosztuje wymiana (0 = arbuzy->eme, 1 = eme->xp)
    String webhook = "";                   // adres webhooka Discord (puste = wylaczone)
    int depositedBottles, cycles;
    final int[] lastSellG = {1, 1};        // ile sztuk daje jedna wymiana
    String lastChatBalance;                // saldo wylapane z czatu (jesli serwer je wypisuje)
    static final HttpClient HTTP = HttpClient.newHttpClient();
    boolean paused;                        // wstrzymany: stan zostaje zachowany i bot wznawia od tego samego miejsca
    boolean split = false;                 // true = podzial po rowno miedzy villagerow (po jednej wymianie), false = shift (wszystko naraz)
    int startAvail = -1, lastAvail = -1, stall;
    final Random R = new Random();
    S s = S.IDLE;
    int delay, waited, taken, trades, step, emptyTicks, vIdx, vPos, quota, used, attempts;
    boolean counted;
    String homeName = "Domek #1";
    S afterHome = S.AIM_V;
    Vec3d startPos = Vec3d.ZERO, jitter = Vec3d.ZERO, homePos = Vec3d.ZERO;
    // --- nawigacja (A*) ---
    record Node(BlockPos pos, double g, double f, Node parent) {}
    List<BlockPos> path; int pi, stuckTicks, repaths; boolean navFail; Vec3d pathTarget = Vec3d.ZERO;
    // --- ucieczka / anty-logout ---
    int antiSec = 30, fleeLeft, fleeRecalc; Vec3d fleeDir;
    boolean blocked;   // ktorys villager nie sprzedawal w tej rundzie
    int lastBuy = 1;   // ile sztuk kosztuje jedna wymiana

    @Override
    public void onInitializeClient() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            Matcher m = CHAT_BAL.matcher(msg.getString());
            if (m.find()) lastChatBalance = m.group(2).trim() + "$";
        });
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> {
            d.register(literal("arbuz-skrzynia").executes(c -> setBlock(c, 0)));
            d.register(literal("arbuz-skrzynia-odluz").executes(c -> setBlock(c, 1)));
            d.register(literal("arbuz-villager-eme").executes(c -> setVillager(c, 0)));
            d.register(literal("arbuz-villager-xp").executes(c -> setVillager(c, 1)));
            d.register(literal("arbuz-villager-eme-clear").executes(c -> { vEme.clear(); save(); c.getSource().sendFeedback(Text.literal("[Arbuz] Wyczyszczono liste villagerow eme.")); return 1; }));
            d.register(literal("arbuz-villager-xp-clear").executes(c -> { vXp.clear(); save(); c.getSource().sendFeedback(Text.literal("[Arbuz] Wyczyszczono liste villagerow xp.")); return 1; }));
            d.register(literal("arbuz-antylogout").then(argument("sekundy", IntegerArgumentType.integer(1, 600)).executes(c -> {
                antiSec = IntegerArgumentType.getInteger(c, "sekundy"); save();
                c.getSource().sendFeedback(Text.literal("[Arbuz] Anty-logout ustawiony na " + antiSec + " s."));
                return 1;
            })));
            d.register(literal("arbuz-webhook").then(argument("url", StringArgumentType.greedyString()).executes(c -> {
                String url = StringArgumentType.getString(c, "url").trim();
                if (!(url.startsWith("https://discord.com/api/webhooks/") || url.startsWith("https://discordapp.com/api/webhooks/"))) {
                    c.getSource().sendFeedback(Text.literal("[Arbuz] To nie wyglada na webhook Discorda (https://discord.com/api/webhooks/...)."));
                    return 0;
                }
                webhook = url; save();
                c.getSource().sendFeedback(Text.literal("[Arbuz] Webhook zapisany. Wysylam wiadomosc testowa..."));
                post("🤖 Test webhooka", COL_BLUE, null, fld("Status", code("Polaczenie dziala"), false), who(), srv());
                return 1;
            })));
            d.register(literal("arbuz-webhook-off").executes(c -> { webhook = ""; save(); c.getSource().sendFeedback(Text.literal("[Arbuz] Webhook wylaczony.")); return 1; }));
            d.register(literal("arbuz-wstrzymaj").executes(c -> { pauseBot(c.getSource().getClient()); return 1; }));
            d.register(literal("arbuz-startuj").executes(c -> { resumeBot(c.getSource().getClient()); return 1; }));
            d.register(literal("arbuz-podzial").executes(c -> {
                split = !split; save();
                c.getSource().sendFeedback(Text.literal("[Arbuz] Podzial po rowno miedzy villagerow: " + (split ? "WLACZONY (wymienia po jednej)" : "WYLACZONY (wymienia wszystko naraz jak na shifcie)")));
                return 1;
            }));
            d.register(literal("arbuz-status").executes(c -> {
                c.getSource().sendFeedback(Text.literal("[Arbuz] skrzynia: " + (chest != null) + ", odluz: " + (outChest != null)
                        + ", villagerzy eme: " + vEme.size() + ", villagerzy xp: " + vXp.size() + ", webhook: " + (webhook.isEmpty() ? "wylaczony" : "wlaczony") + ", wstrzymany: " + paused + ", podzial: " + split + ", anty-logout: " + antiSec + " s, stan: " + s));
                return 1;
            }));
            d.register(literal("arbuz-start").executes(c -> {
                if (chest == null || outChest == null || vEme.isEmpty() || vXp.isEmpty()) {
                    c.getSource().sendFeedback(Text.literal("[Arbuz] Najpierw zaznacz: skrzynia, skrzynia-odluz, villager-eme, villager-xp"));
                    return 0;
                }
                c.getSource().getClient().options.pauseOnLostFocus = false; // bot dziala przy zminimalizowanym oknie
                paused = false; save();
                taken = 0; counted = false;
                go(S.AIM_CHEST, 10, 30);
                c.getSource().sendFeedback(Text.literal("[Arbuz] Start. Czekam na 5 stakow arbuzow w skrzynce."));
                hookStart();
                return 1;
            }));
            d.register(literal("arbuz-stop").executes(c -> { boolean was = s != S.IDLE; s = S.IDLE; paused = false; save(); walk(false); if (was) hookStop(null); c.getSource().sendFeedback(Text.literal("[Arbuz] Stop.")); return 1; }));
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
        if (paused) {
            // wstrzymany - nic nie robi, ale jak ktos go trafi, ucieka (i po anty-logoucie wylogowuje)
            if (mc.player != null && mc.world != null && mc.player.hurtTime > 0) { paused = false; save(); startFlee(mc, mc.player); }
            return;
        }
        if (s == S.IDLE || s == S.DONE) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) { s = S.IDLE; return; }
        if (s == S.FLEE) { flee(mc, p); return; }
        if (p.hurtTime > 0) { startFlee(mc, p); return; }
        if (delay > 0) { delay--; return; }
        if (++waited > 800) { fail("Timeout w kroku " + s); return; }

        ScreenHandler h = p.currentScreenHandler;

        switch (s) {
            case AIM_CHEST -> {
                if (p.getInventory().count(Items.MELON) >= MELON_TARGET) {
                    msg("Mam juz 5 stakow arbuzow w eq.");
                    homeName = "Domek #1"; afterHome = S.AIM_V; vIdx = 0; vPos = 0; attempts = 0; blocked = false; passMade = 0; idlePasses = 0;
                    go(S.HOME_CMD, 10, 30);
                    return;
                }
                if (openBlock(mc, p, chest)) { counted = false; taken = 0; go(S.TAKE, 6, 14); }
            }
            case TAKE -> {
                if (!(h instanceof GenericContainerScreenHandler g)) {
                    if (waited > 40) go(S.AIM_CHEST, 5, 15);
                    return;
                }
                int chestSlots = g.getRows() * 9;
                if (!counted) {
                    int have = p.getInventory().count(Items.MELON);          // reszta arbuzow z poprzedniej tury tez sie liczy
                    needTotal = MELON_TARGET - have; takenTotal = 0;
                    int sum = 0;
                    for (int i = 0; i < chestSlots; i++) { ItemStack st = g.getSlot(i).getStack(); if (st.isOf(Items.MELON)) sum += st.getCount(); }
                    counted = true;
                    if (sum < needTotal) {
                        p.closeHandledScreen();
                        go(S.AIM_CHEST, 600, 1200); // za malo arbuzow - sprawdz ponownie za 30-60 s
                        return;
                    }
                    msg("Mam " + have + " arbuzow, biore " + needTotal + " ze skrzynki (razem 5 stakow).");
                }
                if (takenTotal < needTotal) {
                    int bi = -1, bc = 0;
                    for (int i = 0; i < chestSlots; i++) {
                        ItemStack st = g.getSlot(i).getStack();
                        if (st.isOf(Items.MELON) && st.getCount() > bc) { bi = i; bc = st.getCount(); }
                    }
                    if (bi >= 0) {
                        if (++taken > 20) { fail("Nie moge wziac arbuzow (pelny ekwipunek?)."); return; }
                        mc.interactionManager.clickSlot(g.syncId, bi, 0, SlotActionType.QUICK_MOVE, p);
                        takenTotal += bc; waited = 0;
                        delay = 3 + R.nextInt(7);
                        return;
                    }
                }
                p.closeHandledScreen();
                homeName = "Domek #1"; afterHome = S.AIM_V; vIdx = 0; vPos = 0; attempts = 0; blocked = false; passMade = 0; idlePasses = 0;
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
                    if (afterHome == S.DONE) {
                        // jestem na Domek #2 - bot dziala dalej: wraca do skrzynki z arbuzami i czeka na kolejna ture
                        msg("Jestem na " + homeName + " - czekam na kolejne arbuzy.");
                        cycles++;
                        depositedBottles = 0;
                        taken = 0; counted = false;
                        go(S.AIM_CHEST, 40, 90);
                        return;
                    }
                    if (h != p.playerScreenHandler) p.closeHandledScreen();
                    homePos = p.getPos();
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
                if (bb.getCenter().distanceTo(homePos) > MAX_WALK || dist > MAX_WALK) {
                    msg("Villager #" + (vPos + 1) + " dalej niz " + (int) MAX_WALK + " m - pomijam."); nextVillager(); return;
                }
                if (p.getPos().distanceTo(homePos) > MAX_WALK + 2) {
                    msg("Wyszedlem poza " + (int) MAX_WALK + " m - pomijam villagera #" + (vPos + 1) + "."); nextVillager(); return;
                }
                if (waited > 500) {
                    msg("Nie moge dojsc do villagera #" + (vPos + 1) + " (sciana / przeszkoda?) - pomijam."); nextVillager(); return;
                }
                boolean los = visible(mc, p, v);            // czy widze villagera (bez scian)
                if (!(los && dist <= 2.8)) {
                    // idz sciezka (A*) w miejsce, z ktorego villager jest w zasiegu i widoczny
                    if (navigate(mc, p, n -> goalEntity(mc, n, v), bb.getCenter(), homePos) && ++repaths > 3) navFail = true;
                    if (navFail) { msg("Nie znalazlem drogi do villagera #" + (vPos + 1) + " - pomijam."); nextVillager(); }
                    return;
                }
                moveTo(mc, p, false); path = null;
                boolean aimed = aim(p, bb.getCenter().add(jitter.multiply(0.3)));
                if (aimed) {
                    int have = p.getInventory().count(inputItem());
                    if (have <= 0) { msg("Brak przedmiotow do wymiany - pomijam."); nextVillager(); return; }
                    quota = split ? (int) Math.ceil(have / (double) (grp.size() - vPos)) : Integer.MAX_VALUE; // podzial po rowno (opcjonalny)
                    used = 0; trades = 0; step = 0; emptyTicks = 0; attempts++;
                    startAvail = -1; lastAvail = -1; stall = 0;
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
                // 1) jesli trzymam przedmiot na kursorze - odloz go do ekwipunku
                ItemStack cursor = m.getCursorStack();
                if (!cursor.isEmpty()) {
                    int target = -1;
                    for (int i = 3; i < m.slots.size() && target < 0; i++) {
                        ItemStack st = m.getSlot(i).getStack();
                        if (st.isOf(cursor.getItem()) && st.getCount() < st.getMaxCount()) target = i;
                    }
                    for (int i = 3; i < m.slots.size() && target < 0; i++) if (m.getSlot(i).getStack().isEmpty()) target = i;
                    if (target < 0) { fail("Pelny ekwipunek."); return; }
                    mc.interactionManager.clickSlot(m.syncId, target, 0, SlotActionType.PICKUP, p);
                    waited = 0; delay = 2 + R.nextInt(4);
                    return;
                }
                TradeOfferList offers = m.getRecipes();
                int idx = findOffer(offers);
                if (idx < 0) { msg("Villager #" + (vPos + 1) + " nie ma jeszcze tej wymiany - wroce do niego pozniej."); blocked = true; p.closeHandledScreen(); nextVillager(); return; }
                TradeOffer o = offers.get(idx);
                ItemStack buy = o.getDisplayedFirstBuyItem();
                lastBuy = Math.max(1, buy.getCount());
                lastBuyG[vIdx] = lastBuy;
                lastSellG[vIdx] = Math.max(1, o.getSellItem().getCount());
                if (o.isDisabled() && used < quota) blocked = true; // wyprzedany - wroce po restocku
                // przedmioty wrzucone przez autofill do okienek wymiany tez sie licza (inaczej bot myslal ze nie ma emeraldow)
                int avail = p.getInventory().count(buy.getItem());
                for (int i = 0; i < 2; i++) { ItemStack st = m.getSlot(i).getStack(); if (st.isOf(buy.getItem())) avail += st.getCount(); }
                if (startAvail < 0) startAvail = avail;
                boolean done = o.isDisabled() || used >= quota || avail < buy.getCount() || trades >= 600;
                if (done) {
                    int made = Math.max(0, (startAvail - avail) / lastBuy);
                    passMade += made;
                    if (made > 0) hookBuy(vIdx == 0 ? "Emeraldy" : "Butelki XP", made * lastSellG[vIdx]);
                    msg("Villager #" + (vPos + 1) + " (" + (vIdx == 0 ? "eme" : "xp") + "): " + made + " wymian"
                            + (o.isDisabled() ? " (wyprzedany)" : (split && used >= quota) ? " (jego czesc)" : " (koniec towaru)") + ".");
                    p.closeHandledScreen();
                    nextVillager();
                    return;
                }
                ItemStack out = m.getSlot(2).getStack();
                if (!out.isEmpty()) {
                    if (split) {
                        // jedna wymiana na raz (PICKUP) - zeby dalo sie podzielic po rowno
                        mc.interactionManager.clickSlot(m.syncId, 2, 0, SlotActionType.PICKUP, p);
                        trades++; used += buy.getCount(); step = 0; emptyTicks = 0; waited = 0;
                        delay = 3 + R.nextInt(6);
                        return;
                    }
                    // shift-click: wymienia wszystko co jest w okienkach naraz
                    if (avail == lastAvail) { if (++stall >= 4) { fail("Nie moge odebrac wymiany (pelny ekwipunek?)."); return; } } else stall = 0;
                    lastAvail = avail;
                    mc.interactionManager.clickSlot(m.syncId, 2, 0, SlotActionType.QUICK_MOVE, p);
                    trades++; step = 0; emptyTicks = 0; waited = 0;
                    delay = 3 + R.nextInt(6);
                    return;
                }
                if (step == 0) {
                    m.setRecipeIndex(idx); m.switchTo(idx);
                    mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(idx));
                    step = 1; emptyTicks = 0; waited = 0;
                    delay = 3 + R.nextInt(5);
                } else {
                    if (++emptyTicks > 8) { step = 0; emptyTicks = 0; }
                    delay = 2;
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
                        depositedBottles += g.getSlot(i).getStack().getCount();
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
        s = next; waited = 0; walk(false); path = null; repaths = 0; navFail = false;
        delay = minD + R.nextInt(Math.max(1, maxD - minD + 1));
        jitter = new Vec3d((R.nextDouble() - .5) * .5, (R.nextDouble() - .5) * .5, (R.nextDouble() - .5) * .5);
    }

    void fail(String m) { s = S.IDLE; walk(false); msg("BLAD: " + m); hookStop(m); }

    void msg(String m) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[Arbuz] " + m), false);
    }

    List<UUID> group() { return vIdx == 0 ? vEme : vXp; }

    Item inputItem() { return vIdx == 0 ? Items.MELON : Items.EMERALD; }

    void walk(boolean on) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) { mc.options.forwardKey.setPressed(on); if (!on) { mc.options.jumpKey.setPressed(false); mc.options.sprintKey.setPressed(false); } }
    }

    /** Nastepny villager; po ostatnim w grupie decyduje endPass(). */
    void nextVillager() {
        vPos++; attempts = 0;
        if (vPos < group().size()) { go(S.AIM_V, 15, 40); return; }
        endPass();
    }

    boolean hasInput(ClientPlayerEntity p, int g) {
        return p.getInventory().count(g == 0 ? Items.MELON : Items.EMERALD) >= lastBuyG[g];
    }

    /**
     * Koniec przejscia przez grupe villagerow. Jesli w tej grupie zostal towar (wyprzedani), a druga grupa ma co wymieniac,
     * bot przechodzi do drugiej (eme <-> xp). Gdy obie grupy stoja, czeka na restock. Gdy nic nie zostalo - odklada butelki.
     */
    void endPass() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        idlePasses = passMade == 0 ? idlePasses + 1 : 0;
        passMade = 0;
        int cur = vIdx, other = 1 - vIdx;
        boolean curHas = hasInput(p, cur), otherHas = hasInput(p, other);
        if (!curHas && !otherHas) { go(S.AIM_OUT, 15, 40); return; }   // wszystko wymienione
        int next = otherHas ? other : cur;
        boolean wait = (next == cur && idlePasses >= 1) || idlePasses >= 2;
        vIdx = next; vPos = 0; attempts = 0;
        if (wait) {
            idlePasses = 0;
            msg("Villagerzy nie sprzedaja - czekam na restock i wracam do pracy. (/arbuz-stop zeby przerwac)");
            hookWait(p, next);
            go(S.AIM_V, 600, 1200); // sprawdz ponownie za 30-60 s
        } else {
            if (next != cur) msg("Przechodze do villagerow " + (next == 0 ? "eme (arbuzy)" : "xp (butelki)") + ".");
            go(S.AIM_V, 15, 40);
        }
    }

    /** Czy miedzy mna a villagerem nie ma bloku (zeby nie otwierac przez sciane). */
    boolean visible(MinecraftClient mc, ClientPlayerEntity p, Entity v) { return canSee(mc, p.getEyePos(), v); }

    boolean canSee(MinecraftClient mc, Vec3d eye, Entity v) {
        for (Vec3d t : new Vec3d[]{v.getEyePos(), v.getBoundingBox().getCenter()}) {
            BlockHitResult r = mc.world.raycast(new RaycastContext(eye, t, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
            if (r.getType() == HitResult.Type.MISS || r.getPos().distanceTo(eye) >= eye.distanceTo(t) - 0.25) return true;
        }
        return false;
    }

    boolean goalEntity(MinecraftClient mc, BlockPos n, Entity v) {
        Vec3d eye = new Vec3d(n.getX() + .5, n.getY() + 1.62, n.getZ() + .5);
        Box b = v.getBoundingBox();
        Vec3d c = b.getCenter();
        if (Math.abs(eye.x - c.x) > 3.2 || Math.abs(eye.z - c.z) > 3.2 || Math.abs(eye.y - c.y) > 3.5) return false;
        Vec3d near = new Vec3d(MathHelper.clamp(eye.x, b.minX, b.maxX), MathHelper.clamp(eye.y, b.minY, b.maxY), MathHelper.clamp(eye.z, b.minZ, b.maxZ));
        return eye.distanceTo(near) <= 2.6 && canSee(mc, eye, v);
    }

    boolean blockSeen(MinecraftClient mc, Vec3d eye, BlockPos pos) {
        BlockHitResult r = mc.world.raycast(new RaycastContext(eye, Vec3d.ofCenter(pos), RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        if (r.getType() != HitResult.Type.BLOCK) return false;
        BlockPos bp = r.getBlockPos();
        return bp.equals(pos) || (mc.world.getBlockState(bp).getBlock() == mc.world.getBlockState(pos).getBlock() && bp.getSquaredDistance(pos) <= 2);
    }

    // ================= nawigacja A* =================
    boolean passable(ClientWorld w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        return s.getCollisionShape(w, pos).isEmpty() && s.getFluidState().isEmpty()
                && !s.isOf(Blocks.FIRE) && !s.isOf(Blocks.SOUL_FIRE) && !s.isOf(Blocks.COBWEB)
                && !s.isOf(Blocks.SWEET_BERRY_BUSH) && !s.isOf(Blocks.POWDER_SNOW);
    }

    boolean standable(ClientWorld w, BlockPos pos) {
        if (!passable(w, pos) || !passable(w, pos.up())) return false;
        BlockPos fl = pos.down();
        BlockState b = w.getBlockState(fl);
        return !b.getCollisionShape(w, fl).isEmpty() && !b.isOf(Blocks.MAGMA_BLOCK) && !b.isOf(Blocks.CACTUS)
                && !b.isOf(Blocks.CAMPFIRE) && !b.isOf(Blocks.SOUL_CAMPFIRE);
    }

    List<BlockPos> findPath(ClientWorld w, BlockPos start, Predicate<BlockPos> goal, Vec3d heur, Vec3d origin) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        HashMap<Long, Double> best = new HashMap<>();
        open.add(new Node(start, 0, 0, null));
        best.put(start.asLong(), 0.0);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        int[] dys = {0, 1, -1, -2, -3};
        int iter = 0;
        while (!open.isEmpty() && iter++ < 9000) {
            Node cur = open.poll();
            if (cur.g() > best.getOrDefault(cur.pos().asLong(), 1e18)) continue;
            if (goal.test(cur.pos())) {
                LinkedList<BlockPos> r = new LinkedList<>();
                for (Node n = cur; n != null; n = n.parent()) r.addFirst(n.pos());
                return r;
            }
            BlockPos base = cur.pos();
            for (int[] d : dirs) {
                boolean diag = d[0] != 0 && d[1] != 0;
                for (int dy : dys) {
                    if (diag && dy != 0) continue;
                    BlockPos np = base.add(d[0], dy, d[1]);
                    if (!standable(w, np)) continue;
                    if (dy == 1 && !passable(w, base.up(2))) continue;
                    if (diag && (!passable(w, base.add(d[0], 0, 0)) || !passable(w, base.add(d[0], 1, 0))
                            || !passable(w, base.add(0, 0, d[1])) || !passable(w, base.add(0, 1, d[1])))) continue;
                    if (dy < 0) {
                        boolean ok = true;
                        for (int k = 0; k <= -dy && ok; k++)
                            if (!passable(w, base.add(d[0], -k, d[1])) || !passable(w, base.add(d[0], -k + 1, d[1]))) ok = false;
                        if (!ok) continue;
                    }
                    if (Vec3d.ofBottomCenter(np).distanceTo(origin) > MAX_WALK + 3) continue;
                    double g = cur.g() + (diag ? 1.414 : 1.0) + (dy != 0 ? 0.6 : 0) + (dy < 0 ? -dy * 0.3 : 0);
                    if (g >= best.getOrDefault(np.asLong(), 1e18)) continue;
                    best.put(np.asLong(), g);
                    double hc = Math.max(0, Vec3d.ofBottomCenter(np).distanceTo(heur) - 2.0);
                    open.add(new Node(np, g, g + hc, cur));
                }
            }
        }
        return null;
    }

    /** Idzie sciezka do celu. Zwraca true gdy doszedl do konca sciezki. navFail=true gdy sie nie da. */
    boolean navigate(MinecraftClient mc, ClientPlayerEntity p, Predicate<BlockPos> goal, Vec3d heur, Vec3d origin) {
        if (path != null && pathTarget.distanceTo(heur) > 2.5) path = null; // cel sie ruszyl
        if (path == null) {
            path = findPath(mc.world, p.getBlockPos(), goal, heur, origin);
            pi = 0; stuckTicks = 0; pathTarget = heur;
            if (path == null) { navFail = true; moveTo(mc, p, false); return false; }
        }
        // przeskocz punkty, ktore juz minelismy
        while (pi < path.size()) {
            BlockPos wp = path.get(pi);
            double dh = Math.hypot(p.getX() - (wp.getX() + .5), p.getZ() - (wp.getZ() + .5));
            if (dh < 0.45 && Math.abs(p.getY() - wp.getY()) < 1.3) pi++; else break;
        }
        if (pi >= path.size()) { path = null; moveTo(mc, p, false); return true; }
        BlockPos wp = path.get(pi);
        Vec3d c = new Vec3d(wp.getX() + .5, wp.getY(), wp.getZ() + .5);
        aim(p, new Vec3d(c.x, p.getEyeY(), c.z));
        mc.options.forwardKey.setPressed(true);
        boolean up = c.y > p.getY() + 0.3;
        mc.options.jumpKey.setPressed(p.isOnGround() && (up || p.horizontalCollision));
        double speed = Math.hypot(p.getVelocity().x, p.getVelocity().z);
        if (speed < 0.03) stuckTicks++; else stuckTicks = 0;
        if (stuckTicks > 40) { path = null; stuckTicks = 0; if (++repaths > 3) navFail = true; }
        return false;
    }

    // ================= webhook Discord =================
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> { }
                case '\t' -> b.append(' ');
                default -> { if (ch >= 32) b.append(ch); }
            }
        }
        return b.toString();
    }

    // --- wiadomosci Discord (NK-Arbuz) ---
    static final int COL_GREEN = 5763719, COL_BLUE = 3447003, COL_YELLOW = 16705372, COL_RED = 15548997;
    static final Pattern MONEY1 = Pattern.compile("(\\d[\\d\\s.,]*\\s?[kKmMbB]?)\\s*\\$");
    static final Pattern MONEY2 = Pattern.compile("\\$\\s*(\\d[\\d\\s.,]*[kKmMbB]?)");
    static final Pattern MONEY_KEY = Pattern.compile("(?i)(pieni|monet|stan konta|saldo|balance|kasa|portfel|money)");
    static final Pattern CHAT_BAL = Pattern.compile("(?i)(pieni[aą]dze|saldo|stan konta|balance|portfel)\\D{0,20}(\\d[\\d\\s.,]*[kKmMbB]?)");

    String fld(String name, String value, boolean inline) {
        return "{\"name\":\"" + esc(name) + "\",\"value\":\"" + esc(value) + "\",\"inline\":" + inline + "}";
    }

    String code(String v) { return "`" + v + "`"; }

    String serverName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer";
    }

    String who() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return fld("Gracz", code(mc.getSession() != null ? mc.getSession().getUsername() : "?"), false);
    }

    String srv() { return fld("Serwer", code(serverName()), false); }

    String acct() { return fld("Stan konta", code(balance()), false); }

    /** Stan konta: z tablicy wynikow (scoreboard) po prawej, a jak jej nie ma - z ostatniej wiadomosci na czacie. */
    String balance() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world != null) {
                Scoreboard sb = mc.world.getScoreboard();
                ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
                if (obj != null) {
                    String fallback = null;
                    for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
                        Team team = sb.getScoreHolderTeam(e.owner());
                        String line = Team.decorateName(team, e.name()).getString();
                        Matcher m = MONEY1.matcher(line);
                        if (!m.find()) { m = MONEY2.matcher(line); if (!m.find()) continue; }
                        String val = m.group(1).trim() + "$";
                        if (MONEY_KEY.matcher(line).find()) return val;
                        if (fallback == null) fallback = val;
                    }
                    if (fallback != null) return fallback;
                }
            }
        } catch (Exception ignored) {}
        return lastChatBalance != null ? lastChatBalance : "Brak danych";
    }

    /** Wysyla embed na webhook (asynchronicznie). fields = gotowe fragmenty JSON z fld(). */
    void post(String title, int color, String description, String... fields) {
        if (webhook == null || webhook.isBlank()) return;
        try {
            String json = "{\"username\":\"NK-Arbuz\",\"embeds\":[{\"title\":\"" + esc(title) + "\",\"color\":" + color
                    + (description != null ? ",\"description\":\"" + esc(description) + "\"" : "")
                    + ",\"fields\":[" + String.join(",", fields) + "],\"footer\":{\"text\":\"NK-Arbuz\"}}]}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhook))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    /** Zielona: pomyslnie zakupiono przedmiot (po kazdym villagerze). */
    void hookBuy(String item, int qty) {
        post("🛒   Pomyślnie zakupiono przedmiot!", COL_GREEN, null,
                fld("Przedmiot", code(item), true), fld("Ilość", code(qty + " szt."), true), acct(), who(), srv());
    }

    /** Niebieska: uruchomienie bota. */
    void hookStart() {
        post("🤖 Pomyślnie uruchomiono bota!", COL_BLUE, null,
                fld("Przedmiot", code("Butelka XP & Emerald"), true), fld("Ilość do zakupienia", code("Nielimitowana"), true), acct(), who(), srv());
    }

    /** Zolta: wylaczenie bota (reason = powod, gdy bot sam sie zatrzymal). */
    void hookStop(String reason) {
        if (reason == null) post("🤖 Pomyślnie wyłączono bota!", COL_YELLOW, "**Dziękujemy że nasz używasz**", acct(), who(), srv());
        else post("🤖 Pomyślnie wyłączono bota!", COL_YELLOW, "**Dziękujemy że nasz używasz**", fld("Powód", code(reason), false), acct(), who(), srv());
    }

    /** Czerwona: bot czeka na villagerow (wyprzedani). */
    void hookWait(ClientPlayerEntity p, int g) {
        int input = p.getInventory().count(g == 0 ? Items.MELON : Items.EMERALD);
        int qty = (input / Math.max(1, lastBuyG[g])) * lastSellG[g];
        post("⚠️  Oczekiwanie na villagerów!", COL_RED, null,
                fld("Przedmiot (na który czeka)", code(g == 0 ? "Emeraldy" : "Butelki XP"), true), fld("Ilość", code(qty + " szt."), true), who(), srv());
    }

    // ================= wstrzymaj / wznow =================
    void pauseBot(MinecraftClient mc) {
        if (paused) { msg("Bot jest juz wstrzymany. Wpisz /arbuz-startuj."); return; }
        if (s == S.IDLE || s == S.DONE) { msg("Bot nie dziala - nie ma czego wstrzymywac."); return; }
        if (s == S.FLEE) { msg("Bot ucieka - nie moge go teraz wstrzymac."); return; }
        ClientPlayerEntity p = mc.player;
        if (p != null && p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
        walk(false);
        // kroki z otwartym oknem wznawiam od otwarcia okna
        switch (s) {
            case TAKE -> { s = S.AIM_CHEST; counted = false; taken = 0; }
            case TRADE -> { s = S.AIM_V; attempts = 0; }
            case DEPOSIT -> s = S.AIM_OUT;
            case HOME_GUI -> s = S.HOME_CMD;
            default -> {}
        }
        path = null;
        paused = true;
        save();
        msg("Wstrzymano (krok: " + s + "). Postep zapisany - /arbuz-startuj wznawia od tego miejsca.");
    }

    void resumeBot(MinecraftClient mc) {
        if (!paused) { msg("Bot nie jest wstrzymany."); return; }
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        if (chest == null || outChest == null || vEme.isEmpty() || vXp.isEmpty()) { msg("Brakuje zaznaczen (skrzynie / villagerzy) - sprawdz /arbuz-status."); return; }
        if (homePos.equals(Vec3d.ZERO)) homePos = p.getPos();
        paused = false;
        save();
        S next = s;
        go(next, 10, 30);
        msg("Wznowiono od kroku " + next + ".");
    }

    // ================= ucieczka + anty-logout =================
    void startFlee(MinecraftClient mc, ClientPlayerEntity p) {
        if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
        walk(false);
        fleeLeft = antiSec * 20 + 20; fleeRecalc = 0; fleeDir = null; path = null;
        s = S.FLEE;
        msg("Dostalem obrazenia - ucieka. Po " + antiSec + " s bez trafienia wyloguje sie.");
    }

    void flee(MinecraftClient mc, ClientPlayerEntity p) {
        if (p.hurtTime > 0) fleeLeft = antiSec * 20 + 20; // kazde trafienie restartuje anty-logout
        if (--fleeLeft <= 0) {
            walk(false);
            msg("Koniec anty-logout - wylogowuje.");
            hookStop("Koniec anty-logout - wylogowano z serwera");
            s = S.IDLE;
            if (mc.getNetworkHandler() != null)
                mc.getNetworkHandler().getConnection().disconnect(Text.literal("[ArbuzBot] Koniec anty-logout - wylogowano."));
            return;
        }
        if (fleeRecalc-- <= 0) { fleeRecalc = 8; fleeDir = bestDirection(mc, p); }
        if (fleeDir == null) { walk(false); return; }
        aim(p, p.getEyePos().add(fleeDir.multiply(5)));
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(p.horizontalCollision && p.isOnGround());
    }

    /** Wybiera kierunek ucieczki: tam gdzie jest wolne miejsce, najlepiej od najblizszego gracza/moba. */
    Vec3d bestDirection(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d pos = p.getPos(), threat = null;
        double bd = 16;
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == p) continue;
            double d = o.getPos().distanceTo(pos);
            if (d < bd) { bd = d; threat = o.getPos(); }
        }
        if (threat == null) {
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof HostileEntity)) continue;
                double d = e.getPos().distanceTo(pos);
                if (d < bd) { bd = d; threat = e.getPos(); }
            }
        }
        Vec3d best = null; double bestScore = -1e9;
        for (int i = 0; i < 16; i++) {
            double ang = i * Math.PI / 8;
            Vec3d dir = new Vec3d(Math.cos(ang), 0, Math.sin(ang));
            double free = freeDist(mc, p, dir, 10);
            if (free < 1.2) continue;
            BlockPos ahead = BlockPos.ofFloored(pos.add(dir.multiply(Math.min(free, 2.5))));
            boolean ground = false;
            for (int k = 1; k <= 3 && !ground; k++)
                if (!mc.world.getBlockState(ahead.down(k)).getCollisionShape(mc.world, ahead.down(k)).isEmpty()) ground = true;
            if (!ground || !mc.world.getFluidState(ahead).isEmpty()) continue; // klif / woda
            double score = Math.min(free, 10);
            if (threat != null) {
                Vec3d away = pos.subtract(threat).multiply(1, 0, 1);
                if (away.lengthSquared() > 1e-4) score += 6 * dir.dotProduct(away.normalize());
            }
            if (score > bestScore) { bestScore = score; best = dir; }
        }
        return best;
    }

    double freeDist(MinecraftClient mc, ClientPlayerEntity p, Vec3d dir, double max) {
        double m = max;
        for (double h : new double[]{0.3, 1.7}) {
            Vec3d a = p.getPos().add(0, h, 0);
            BlockHitResult r = mc.world.raycast(new RaycastContext(a, a.add(dir.multiply(max)), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
            if (r.getType() == HitResult.Type.BLOCK) m = Math.min(m, r.getPos().distanceTo(a));
        }
        return m;
    }

    /** Ruch do przodu; przy przeszkodzie (kolizja) skacze jak gracz. */
    void moveTo(MinecraftClient mc, ClientPlayerEntity p, boolean forward) {
        mc.options.forwardKey.setPressed(forward);
        mc.options.jumpKey.setPressed(forward && p.horizontalCollision && p.isOnGround());
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

    /** Podchodzi do skrzynki sciezka (max 30 m), celuje plynnie i otwiera ja gdy faktycznie na nia patrzy. */
    boolean openBlock(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos) {
        Vec3d c = Vec3d.ofCenter(pos);
        double d = p.getEyePos().distanceTo(c);
        if (d > MAX_WALK) { fail("Skrzynka " + pos.toShortString() + " dalej niz " + (int) MAX_WALK + " m."); return false; }
        if (!(d <= 4.0 && blockSeen(mc, p.getEyePos(), pos))) {
            Vec3d origin = p.getPos();
            if (navigate(mc, p, n -> {
                Vec3d eye = new Vec3d(n.getX() + .5, n.getY() + 1.62, n.getZ() + .5);
                return eye.distanceTo(c) <= 3.8 && blockSeen(mc, eye, pos);
            }, c, origin) && ++repaths > 3) navFail = true;
            if (navFail) fail("Nie moge dojsc do skrzynki " + pos.toShortString() + ".");
            return false;
        }
        moveTo(mc, p, false); path = null;
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
            pr.setProperty("antiSec", antiSec + "");
            pr.setProperty("split", split + "");
            pr.setProperty("webhook", webhook);
            if (paused) { // zapisany postep wstrzymanego bota
                pr.setProperty("pState", s.name());
                pr.setProperty("vIdx", vIdx + "");
                pr.setProperty("vPos", vPos + "");
                pr.setProperty("homeName", homeName);
                pr.setProperty("afterHome", afterHome.name());
                pr.setProperty("blocked", blocked + "");
                pr.setProperty("homePos", homePos.x + "," + homePos.y + "," + homePos.z);
            }
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
            antiSec = Integer.parseInt(pr.getProperty("antiSec", "30"));
            split = Boolean.parseBoolean(pr.getProperty("split", "false"));
            webhook = pr.getProperty("webhook", "");
            if (pr.containsKey("pState")) { // przywroc wstrzymany postep (po restarcie gry)
                s = S.valueOf(pr.getProperty("pState"));
                vIdx = Integer.parseInt(pr.getProperty("vIdx", "0"));
                vPos = Integer.parseInt(pr.getProperty("vPos", "0"));
                homeName = pr.getProperty("homeName", "Domek #1");
                afterHome = S.valueOf(pr.getProperty("afterHome", "AIM_V"));
                blocked = Boolean.parseBoolean(pr.getProperty("blocked", "false"));
                String[] hp = pr.getProperty("homePos", "0,0,0").split(",");
                homePos = new Vec3d(Double.parseDouble(hp[0]), Double.parseDouble(hp[1]), Double.parseDouble(hp[2]));
                paused = (s != S.IDLE && s != S.DONE);
            }
            parse(pr.getProperty("vEme", ""), vEme);
            parse(pr.getProperty("vXp", ""), vXp);
        } catch (Exception ignored) {}
    }
}
