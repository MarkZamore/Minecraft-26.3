package lanmc.worldmenu.probe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import lanmc.worldmenu.WorldMenuPolicy;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import sun.misc.Unsafe;

/** Constructors and final navigation are isolated; widgets, removal, event and current-screen getter are native. */
public final class NativeWorldMenuProbe {
    private static final String WORLD_KEY = "options.worldOptions.button";
    private static final Unsafe UNSAFE = unsafe();
    private static final Method ADD = method("addRenderableWidget", GuiEventListener.class);
    private static final Method REMOVE = method("removeWidget", GuiEventListener.class);
    private static int assertions;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        var jar = Path.of(args[0]);
        annotations(jar);
        Component nativeWorldCaption = nativePauseShape();
        policy(nativeWorldCaption);
        metadata(jar, Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        worldOptionsFallback();
        var proof = new java.util.LinkedHashMap<String, Object>(Map.of(
                "assertions", assertions, "nativePauseScreen", true, "nativeButton", true,
                "nativeNeoForgeInitPost", true, "nativeScreenRemovalFocusAndLists", true,
                "nativeClientAndServerMetadataCodec", true, "clientOnlyAnnotations", true,
                "gameMainStarted", false, "rendererStarted", false,
                "lifecycle", "direct repeated Init.Post plus native widget-list rebuild; no Screen.resize or mod-loader discovery"));
        proof.put("nativePauseWorldOptionsProducer", "actual WORLD_OPTIONS translated key; unique native builder/Supplier/WorldOptionsScreen constructor chain");
        proof.put("nativeShapeNegativeCases", 6);
        proof.put("worldOptionsFallback", "real Minecraft singleton/Gui.screen getter; recording subclass intercepts only final onClose navigation");
        proof.put("nativeWorldOptionsClose", "exact parent-only onClose body and inherited empty removed; malformed native bodies rejected");
        System.out.println("NATIVE_WORLD_MENU_PASS " + new com.google.gson.Gson().toJson(proof));
    }

    private static void policy(Component nativeWorldCaption) throws Exception {
        PauseScreen pause = screen(PauseScreen.class);
        AtomicInteger optionsPresses = new AtomicInteger();
        AtomicInteger lanPresses = new AtomicInteger();
        AtomicInteger reportPresses = new AtomicInteger();
        AtomicInteger removedPresses = new AtomicInteger();
        Button world = button(nativeWorldCaption, 10, removedPresses);
        Button secondWorld = button(nativeWorldCaption, 40, removedPresses);
        Button options = button(Component.translatable("menu.options"), 70, optionsPresses);
        Button lan = button(Component.translatable("menu.multiplayerOptions.button"), 100, lanPresses);
        Button reports = button(Component.translatable("menu.playerReporting"), 130, reportPresses);
        Button literal = button(Component.literal(Component.translatable(WORLD_KEY).getString()), 160, new AtomicInteger());
        Button literalKey = button(Component.literal(WORLD_KEY), 190, new AtomicInteger());
        Button nearKey = button(Component.translatable(WORLD_KEY + ".extra"), 220, new AtomicInteger());
        Button nested = button(Component.literal("Other ").append(Component.translatable(WORLD_KEY)), 250, new AtomicInteger());
        StringWidget text = (StringWidget) UNSAFE.allocateInstance(StringWidget.class);
        Field message = AbstractWidget.class.getDeclaredField("message");
        message.setAccessible(true); message.set(text, Component.translatable(WORLD_KEY));
        List<GuiEventListener> retained = List.of(options, lan, reports, literal, literalKey, nearKey, nested, text);
        add(pause, world); add(pause, secondWorld);
        for (var item : retained) add(pause, item);
        pause.setFocused(world);
        check(pause.getFocused() == world && world.isFocused(), "native focus established");
        var event = event(pause);
        boolean liveReadOnly = false;
        try { event.getListenersList().clear(); } catch (UnsupportedOperationException expected) { liveReadOnly = true; }
        check(liveReadOnly, "real event exposes an unmodifiable listener list");
        WorldMenuPolicy.onScreenInit(event);
        check(pause.children().equals(retained), "only both exact translated world-options buttons removed");
        check(list(pause, "renderables").equals(retained), "native render list contains exactly retained entries");
        check(list(pause, "narratables").equals(retained), "native narration list contains exactly retained entries");
        check(pause.getFocused() == null && !world.isFocused(), "native removal clears screen and widget focus");
        check(pause.getChildAt(15, 15).isEmpty(), "removed world-options button cannot be hit-tested");
        check(!pause.mouseClicked(new MouseButtonEvent(15, 15, new MouseButtonInfo(1, 0)), false),
                "native screen click dispatch cannot reach removed world-options action");
        check(removedPresses.get() == 0, "world-options action was never invoked");
        for (Button kept : List.of(options, lan, reports)) {
            check(pause.getChildAt(kept.getX() + 1, kept.getY() + 1).orElseThrow() == kept,
                    "kept native control remains hit-testable");
            kept.onPress(new KeyEvent(13, 0, 0));
        }
        check(optionsPresses.get() == 1 && lanPresses.get() == 1 && reportPresses.get() == 1,
                "native Options, LAN and Reports callbacks remain unchanged");
        WorldMenuPolicy.onScreenInit(event);
        check(pause.children().equals(retained), "same event is idempotent");
        pause.setFocused(lan);
        Button rebuiltWorld = button(nativeWorldCaption, 10, removedPresses);
        add(pause, rebuiltWorld);
        WorldMenuPolicy.onScreenInit(event(pause));
        check(pause.children().equals(retained) && pause.getFocused() == lan && lan.isFocused(),
                "a repeated init removes a new entry and preserves unrelated focus");
        list(pause, "children").clear(); list(pause, "renderables").clear(); list(pause, "narratables").clear();
        pause.setFocused((GuiEventListener) null);
        Button rebuiltOptions = button(Component.translatable("menu.options"), 70, optionsPresses);
        add(pause, button(nativeWorldCaption, 10, removedPresses)); add(pause, rebuiltOptions);
        WorldMenuPolicy.onScreenInit(event(pause));
        check(pause.children().equals(List.of(rebuiltOptions)) && list(pause, "renderables").equals(List.of(rebuiltOptions))
                && list(pause, "narratables").equals(List.of(rebuiltOptions)), "native lists rebuilt for another init remain filtered");
        OtherScreen other = screen(OtherScreen.class);
        Button otherWorld = button(Component.translatable(WORLD_KEY), 10, removedPresses);
        add(other, otherWorld); other.setFocused(otherWorld);
        WorldMenuPolicy.onScreenInit(event(other));
        check(other.children().equals(List.of(otherWorld)) && list(other, "renderables").contains(otherWorld)
                && list(other, "narratables").contains(otherWorld) && other.getFocused() == otherWorld,
                "a non-pause screen with the exact key is untouched");
    }

    private static Component nativePauseShape() throws Exception {
        Field field = PauseScreen.class.getDeclaredField("WORLD_OPTIONS");
        requireShape(java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == Component.class,
                "native WORLD_OPTIONS field changed");
        field.setAccessible(true);
        Component caption = (Component) field.get(null);
        requireCaption(caption);
        byte[] bytes;
        try (var stream = PauseScreen.class.getResourceAsStream("PauseScreen.class")) {
            if (stream == null) throw new AssertionError("Actual native PauseScreen class bytes are missing");
            bytes = stream.readAllBytes();
        }
        verifyPauseShape(readClass(bytes));
        check(true, "actual native WORLD_OPTIONS caption and unique screen producer are proven");

        expectShapeRejection(() -> requireCaption(Component.translatable(WORLD_KEY + ".renamed")),
                "translation key changed", "renamed actual caption is rejected");
        ClassNode missingField = readClass(bytes);
        MethodNode missingMenu = pauseMenu(missingField);
        missingMenu.instructions.remove(worldFieldUse(missingMenu));
        expectShapeRejection(() -> verifyPauseShape(missingField), "unique WORLD_OPTIONS field read",
                "removed native caller is rejected");
        ClassNode duplicateField = readClass(bytes);
        MethodNode duplicateMenu = pauseMenu(duplicateField);
        FieldInsnNode original = worldFieldUse(duplicateMenu);
        duplicateMenu.instructions.insert(original, new FieldInsnNode(original.getOpcode(), original.owner, original.name, original.desc));
        expectShapeRejection(() -> verifyPauseShape(duplicateField), "unique WORLD_OPTIONS field read",
                "duplicate native caller is rejected");
        ClassNode changedBuilder = readClass(bytes);
        MethodNode changedMenu = pauseMenu(changedBuilder);
        var changedOps = instructions(changedMenu);
        ((MethodInsnNode) changedOps.get(changedOps.indexOf(worldFieldUse(changedMenu)) + 3)).desc =
                "(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/screens/Screen;";
        expectShapeRejection(() -> verifyPauseShape(changedBuilder), "native openScreenButton descriptor changed",
                "changed native builder descriptor is rejected");
        ClassNode changedProducer = readClass(bytes);
        MethodNode producer = worldSupplier(changedProducer, pauseMenu(changedProducer));
        ((TypeInsnNode) instructions(producer).getFirst()).desc = "net/minecraft/client/gui/screens/options/OptionsScreen";
        expectShapeRejection(() -> verifyPauseShape(changedProducer), "native WorldOptionsScreen constructor producer changed",
                "changed native screen producer is rejected");
        ClassNode changedSupplier = readClass(bytes);
        MethodNode supplierMenu = pauseMenu(changedSupplier);
        var supplierOps = instructions(supplierMenu);
        var indy = (InvokeDynamicInsnNode) supplierOps.get(supplierOps.indexOf(worldFieldUse(supplierMenu)) + 2);
        Handle implementation = (Handle) indy.bsmArgs[1];
        indy.bsmArgs[1] = new Handle(implementation.getTag(), implementation.getOwner(), implementation.getName(),
                "()Ljava/lang/Object;", implementation.isInterface());
        expectShapeRejection(() -> verifyPauseShape(changedSupplier), "native WorldOptionsScreen Supplier implementation changed",
                "changed native supplier descriptor is rejected");
        return caption;
    }

    private static final String PAUSE = "net/minecraft/client/gui/screens/PauseScreen";
    private static final String WORLD = "net/minecraft/client/gui/screens/WorldOptionsScreen";
    private static final String COMPONENT = "Lnet/minecraft/network/chat/Component;";
    private static final String OPEN_BUTTON = "(" + COMPONENT + "Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;";

    private static void requireCaption(Component caption) {
        requireShape(caption != null && caption.getContents() instanceof TranslatableContents translated
                && WORLD_KEY.equals(translated.getKey()), "native WORLD_OPTIONS translation key changed");
    }
    private static void verifyPauseShape(ClassNode node) {
        requireShape(node.name.equals(PAUSE), "actual native PauseScreen class identity changed");
        var fields = node.fields.stream().filter(field -> field.name.equals("WORLD_OPTIONS")).toList();
        requireShape(fields.size() == 1 && fields.getFirst().desc.equals(COMPONENT)
                && (fields.getFirst().access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL),
                "native WORLD_OPTIONS field changed");
        MethodNode menu = pauseMenu(node);
        FieldInsnNode world = worldFieldUse(menu);
        var ops = instructions(menu);
        int index = ops.indexOf(world);
        requireShape(index > 0 && index + 3 < ops.size() && aload0(ops.get(index - 1)) && aload0(ops.get(index + 1)),
                "native WORLD_OPTIONS receiver/capture producer changed");
        AbstractInsnNode builderNode = ops.get(index + 3);
        requireShape(builderNode instanceof MethodInsnNode builder && builder.getOpcode() == Opcodes.INVOKEVIRTUAL
                && !builder.itf && builder.owner.equals(PAUSE) && builder.name.equals("openScreenButton") && builder.desc.equals(OPEN_BUTTON),
                "native openScreenButton descriptor changed");
        requireShape(node.methods.stream().filter(method -> method.name.equals("openScreenButton") && method.desc.equals(OPEN_BUTTON)).count() == 1,
                "native openScreenButton declaration changed");
        MethodNode supplier = worldSupplier(node, menu);
        var body = instructions(supplier);
        requireShape(body.size() == 8 && body.get(0) instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                && allocation.desc.equals(WORLD) && body.get(1).getOpcode() == Opcodes.DUP
                && aload0(body.get(2)) && aload0(body.get(3)) && body.get(4) instanceof FieldInsnNode minecraft
                && minecraft.getOpcode() == Opcodes.GETFIELD && minecraft.owner.equals(PAUSE) && minecraft.name.equals("minecraft")
                && minecraft.desc.equals("Lnet/minecraft/client/Minecraft;") && body.get(5) instanceof FieldInsnNode level
                && level.getOpcode() == Opcodes.GETFIELD && level.owner.equals("net/minecraft/client/Minecraft") && level.name.equals("level")
                && level.desc.equals("Lnet/minecraft/client/multiplayer/ClientLevel;") && body.get(6) instanceof MethodInsnNode constructor
                && constructor.getOpcode() == Opcodes.INVOKESPECIAL && constructor.owner.equals(WORLD) && constructor.name.equals("<init>")
                && constructor.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/world/level/Level;)V")
                && body.get(7).getOpcode() == Opcodes.ARETURN,
                "native WorldOptionsScreen constructor producer changed");
    }
    private static MethodNode pauseMenu(ClassNode node) {
        var methods = node.methods.stream().filter(method -> method.name.equals("createPauseMenu") && method.desc.equals("()V")
                && (method.access & Opcodes.ACC_STATIC) == 0).toList();
        requireShape(methods.size() == 1, "native createPauseMenu declaration changed");
        return methods.getFirst();
    }
    private static FieldInsnNode worldFieldUse(MethodNode menu) {
        var uses = instructions(menu).stream().filter(insn -> insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(PAUSE) && field.name.equals("WORLD_OPTIONS") && field.desc.equals(COMPONENT)).toList();
        requireShape(uses.size() == 1, "expected unique WORLD_OPTIONS field read in native createPauseMenu");
        return (FieldInsnNode) uses.getFirst();
    }
    private static MethodNode worldSupplier(ClassNode node, MethodNode menu) {
        var ops = instructions(menu);
        int index = ops.indexOf(worldFieldUse(menu));
        requireShape(index + 2 < ops.size() && ops.get(index + 2) instanceof InvokeDynamicInsnNode,
                "native WorldOptionsScreen Supplier producer changed");
        var indy = (InvokeDynamicInsnNode) ops.get(index + 2);
        requireShape(indy.name.equals("get") && indy.desc.equals("(L" + PAUSE + ";)Ljava/util/function/Supplier;")
                && indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && indy.bsm.getName().equals("metafactory")
                && indy.bsmArgs.length == 3 && indy.bsmArgs[1] instanceof Handle,
                "native WorldOptionsScreen Supplier producer changed");
        Handle handle = (Handle) indy.bsmArgs[1];
        requireShape(handle.getOwner().equals(PAUSE) && handle.getDesc().equals("()Lnet/minecraft/client/gui/screens/Screen;")
                && (handle.getTag() == Opcodes.H_INVOKEVIRTUAL || handle.getTag() == Opcodes.H_INVOKESPECIAL) && !handle.isInterface(),
                "native WorldOptionsScreen Supplier implementation changed");
        var methods = node.methods.stream().filter(method -> method.name.equals(handle.getName()) && method.desc.equals(handle.getDesc())
                && (method.access & Opcodes.ACC_STATIC) == 0).toList();
        requireShape(methods.size() == 1, "native WorldOptionsScreen Supplier implementation changed");
        return methods.getFirst();
    }
    private static boolean aload0(AbstractInsnNode instruction) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD && variable.var == 0;
    }
    private static List<AbstractInsnNode> instructions(MethodNode method) {
        var result = new ArrayList<AbstractInsnNode>();
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }
    private static ClassNode readClass(byte[] bytes) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
    private static void requireShape(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void expectShapeRejection(Runnable validation, String expectedReason, String message) {
        boolean rejected = false;
        try { validation.run(); }
        catch (AssertionError failure) { rejected = failure.getMessage().contains(expectedReason); }
        check(rejected, message);
    }

    private static void worldOptionsFallback() throws Exception {
        ClassNode nativeWorld = readNativeClass(WorldOptionsScreen.class);
        ClassNode nativeScreen = readNativeClass(Screen.class);
        verifyWorldCloseShape(nativeWorld, nativeScreen);
        check(WorldOptionsScreen.class.getMethod("removed").getDeclaringClass() == Screen.class,
                "WorldOptions inherits the native empty removed callback");
        check(true, "actual WorldOptions onClose is parent-only navigation without settings application");
        var badClose = readNativeClass(WorldOptionsScreen.class);
        var badCloseMethod = namedMethod(badClose, "onClose", "()V");
        badCloseMethod.instructions.insertBefore(instructions(badCloseMethod).getLast(), new MethodInsnNode(Opcodes.INVOKESTATIC,
                WORLD, "unsafeApplySettings", "()V", false));
        expectShapeRejection(() -> verifyWorldCloseShape(badClose, nativeScreen), "navigation-only onClose changed",
                "an extra settings mutator in native onClose is rejected");
        var badRemoved = readNativeClass(Screen.class);
        var badRemovedMethod = namedMethod(badRemoved, "removed", "()V");
        badRemovedMethod.instructions.insertBefore(instructions(badRemovedMethod).getLast(), new MethodInsnNode(Opcodes.INVOKESTATIC,
                WORLD, "unsafeApplySettings", "()V", false));
        expectShapeRejection(() -> verifyWorldCloseShape(nativeWorld, badRemoved), "inherited removed callback changed",
                "an extra settings mutator in inherited removed is rejected");

        Field singleton = Minecraft.class.getDeclaredField("instance"); singleton.setAccessible(true);
        Field minecraftGui = Minecraft.class.getDeclaredField("gui"); minecraftGui.setAccessible(true);
        Field current = Gui.class.getDeclaredField("screen"); current.setAccessible(true);
        Object previous = singleton.get(null);
        check(previous == null, "no Minecraft instance was constructed by the offline probe");
        Minecraft minecraft = (Minecraft) UNSAFE.allocateInstance(Minecraft.class);
        Gui gui = (Gui) UNSAFE.allocateInstance(Gui.class);
        minecraftGui.set(minecraft, gui);
        singleton.set(null, minecraft);
        try {
            check(Minecraft.getInstance() == minecraft && minecraft.gui == gui,
                    "the actual native singleton getter and final Gui field expose the fixture state");
            PauseScreen parent = screen(PauseScreen.class);
            RecordingWorldOptions visible = screen(RecordingWorldOptions.class);
            visible.parent = parent;
            AtomicInteger settingsActions = new AtomicInteger();
            Button settings = button(Component.translatable("selectWorld.gameMode"), 10, settingsActions);
            add(visible, settings);
            current.set(gui, visible);
            check(gui.screen() == visible, "the actual Gui.screen getter exposes the visible native world-options subtype");
            var visibleEvent = event(visible);
            WorldMenuPolicy.onScreenInit(visibleEvent);
            check(visible.closes == 1 && gui.screen() == parent,
                    "a failed-publication world-options screen closes once through the navigation boundary");
            check(visible.children().equals(List.of(settings)) && settingsActions.get() == 0,
                    "fallback does not invoke settings callbacks or rewrite their native widget list");
            WorldMenuPolicy.onScreenInit(visibleEvent);
            check(visible.closes == 1 && gui.screen() == parent, "repeated post-init after closing cannot close the parent");

            RecordingWorldOptions published = screen(RecordingWorldOptions.class);
            published.parent = parent;
            current.set(gui, null);
            WorldMenuPolicy.onScreenInit(event(published));
            check(published.closes == 0 && gui.screen() == null,
                    "already closed successful LAN publication remains closed");
            OtherScreen replacement = screen(OtherScreen.class);
            current.set(gui, replacement);
            WorldMenuPolicy.onScreenInit(event(published));
            check(published.closes == 0 && gui.screen() == replacement,
                    "a replacement screen is not closed by a stale world-options event");
            RecordingWorldOptions newerWorld = screen(RecordingWorldOptions.class);
            newerWorld.parent = parent;
            current.set(gui, newerWorld);
            WorldMenuPolicy.onScreenInit(event(published));
            check(published.closes == 0 && newerWorld.closes == 0 && gui.screen() == newerWorld,
                    "screen instance identity protects a newer world-options screen from a stale event");
            WorldMenuPolicy.onScreenInit(event(replacement));
            check(gui.screen() == newerWorld, "a non-pause/non-world event does not navigate");
        } finally {
            singleton.set(null, previous);
        }
        check(Minecraft.getInstance() == previous, "native singleton restored after fixture navigation");
    }

    private static void verifyWorldCloseShape(ClassNode world, ClassNode screen) {
        requireShape(world.name.equals(WORLD) && world.superName.equals("net/minecraft/client/gui/screens/Screen"),
                "native world-options screen inheritance changed");
        var body = instructions(namedMethod(world, "onClose", "()V"));
        requireShape(body.size() == 7 && aload0(body.get(0)) && body.get(1) instanceof FieldInsnNode minecraft
                && minecraft.getOpcode() == Opcodes.GETFIELD && minecraft.owner.equals(WORLD) && minecraft.name.equals("minecraft")
                && minecraft.desc.equals("Lnet/minecraft/client/Minecraft;") && body.get(2) instanceof FieldInsnNode gui
                && gui.getOpcode() == Opcodes.GETFIELD && gui.owner.equals("net/minecraft/client/Minecraft") && gui.name.equals("gui")
                && gui.desc.equals("Lnet/minecraft/client/gui/Gui;") && aload0(body.get(3)) && body.get(4) instanceof FieldInsnNode parent
                && parent.getOpcode() == Opcodes.GETFIELD && parent.owner.equals(WORLD) && parent.name.equals("lastScreen")
                && parent.desc.equals("Lnet/minecraft/client/gui/screens/Screen;") && body.get(5) instanceof MethodInsnNode navigation
                && navigation.getOpcode() == Opcodes.INVOKEVIRTUAL && navigation.owner.equals("net/minecraft/client/gui/Gui")
                && navigation.name.equals("setScreen") && navigation.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;)V")
                && !navigation.itf && body.get(6).getOpcode() == Opcodes.RETURN,
                "native WorldOptions navigation-only onClose changed");
        requireShape(world.methods.stream().noneMatch(method -> method.name.equals("removed") && method.desc.equals("()V")),
                "native WorldOptions inherited removed callback changed");
        var removed = instructions(namedMethod(screen, "removed", "()V"));
        requireShape(removed.size() == 1 && removed.getFirst().getOpcode() == Opcodes.RETURN,
                "native Screen inherited removed callback changed");
    }
    private static MethodNode namedMethod(ClassNode node, String name, String descriptor) {
        var found = node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)
                && (method.access & Opcodes.ACC_STATIC) == 0).toList();
        requireShape(found.size() == 1, "native method declaration changed: " + name + descriptor);
        return found.getFirst();
    }
    private static ClassNode readNativeClass(Class<?> type) throws Exception {
        try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (stream == null) throw new AssertionError("Native class resource missing: " + type.getName());
            return readClass(stream.readAllBytes());
        }
    }

    private static void metadata(Path jar, int resourceMajor, int resourceMinor, int dataMajor, int dataMinor) throws Exception {
        // Registry bootstrap is offline metadata setup, not a client or server main.
        Bootstrap.bootStrap();
        JsonObject pack;
        try (var archive = new ZipFile(jar.toFile())) {
            pack = JsonParser.parseString(new String(archive.getInputStream(archive.getEntry("pack.mcmeta")).readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("pack");
        }
        for (var entry : List.of(Map.entry(PackType.CLIENT_RESOURCES, new PackFormat(resourceMajor, resourceMinor)),
                Map.entry(PackType.SERVER_DATA, new PackFormat(dataMajor, dataMinor)))) {
            var codec = PackMetadataSection.forPackType(entry.getKey()).codec();
            var result = codec.parse(JsonOps.INSTANCE, pack);
            var parsed = result.result().orElseThrow(() -> new AssertionError("native metadata codec rejected pack.mcmeta: " + result));
            check(parsed.description().getString().equals("World Menu Policy"), "native metadata description parsed");
            check(parsed.supportedFormats().isValueInRange(entry.getValue()), "native metadata includes current pack format");
            JsonObject bad = pack.deepCopy(); bad.remove("description");
            check(codec.parse(JsonOps.INSTANCE, bad).error().isPresent(), "native metadata codec rejects a malformed description");
        }
    }

    private static void annotations(Path jar) throws Exception {
        var node = new ClassNode();
        try (var archive = new ZipFile(jar.toFile())) {
            new ClassReader(archive.getInputStream(archive.getEntry("lanmc/worldmenu/WorldMenuPolicy.class"))).accept(node, 0);
        }
        var annotations = new ArrayList<AnnotationNode>();
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
        AnnotationNode mod = annotation(annotations, "/Mod;");
        AnnotationNode subscriber = annotation(annotations, "/EventBusSubscriber;");
        check("lanmc_world_menu".equals(value(mod, "value")), "native mod annotation has expected identity");
        check(clientOnly(value(mod, "dist")), "mod entry point explicitly restricted to CLIENT");
        check(clientOnly(value(subscriber, "value")), "event subscriber explicitly restricted to CLIENT");
        var handler = node.methods.stream().filter(method -> method.name.equals("onScreenInit")).findFirst().orElseThrow();
        check(handler.desc.equals("(Lnet/neoforged/neoforge/client/event/ScreenEvent$Init$Post;)V"), "handler receives real Init.Post");
        var methodAnnotations = new ArrayList<AnnotationNode>();
        if (handler.visibleAnnotations != null) methodAnnotations.addAll(handler.visibleAnnotations);
        if (handler.invisibleAnnotations != null) methodAnnotations.addAll(handler.invisibleAnnotations);
        Object priority = value(annotation(methodAnnotations, "/SubscribeEvent;"), "priority");
        check(priority instanceof String[] pair && pair[1].equals("LOWEST"), "handler subscribes after native initialization");
    }

    private static boolean clientOnly(Object value) {
        return value instanceof List<?> items && items.size() == 1 && items.getFirst() instanceof String[] pair
                && pair[0].endsWith("/Dist;") && pair[1].equals("CLIENT");
    }
    private static AnnotationNode annotation(List<AnnotationNode> list, String suffix) {
        var found = list.stream().filter(item -> item.desc.endsWith(suffix)).toList();
        check(found.size() == 1, "one exact expected annotation " + suffix);
        return found.getFirst();
    }
    private static Object value(AnnotationNode node, String key) {
        for (int i = 0; node.values != null && i < node.values.size(); i += 2)
            if (node.values.get(i).equals(key)) return node.values.get(i + 1);
        throw new AssertionError("Missing annotation property: " + key);
    }
    private static Button button(Component caption, int x, AtomicInteger presses) {
        return Button.builder(caption, ignored -> presses.incrementAndGet()).bounds(x, 10, 20, 20).build();
    }
    private static ScreenEvent.Init.Post event(Screen screen) throws Exception {
        @SuppressWarnings("unchecked") var children = (List<GuiEventListener>) screen.children();
        return new ScreenEvent.Init.Post(screen, children, item -> invoke(ADD, screen, item), item -> invoke(REMOVE, screen, item));
    }
    private static void add(Screen screen, GuiEventListener widget) { invoke(ADD, screen, widget); }
    private static void invoke(Method method, Screen screen, Object value) {
        try { method.invoke(screen, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Native Screen method failed", failure); }
    }
    private static Method method(String name, Class<?> parameter) {
        try { var method = Screen.class.getDeclaredMethod(name, parameter); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static <T extends Screen> T screen(Class<T> type) throws Exception {
        T screen = type.cast(UNSAFE.allocateInstance(type));
        for (String name : List.of("children", "renderables", "narratables")) {
            Field field = Screen.class.getDeclaredField(name);
            field.setAccessible(true); field.set(screen, new ArrayList<>());
        }
        screen.width = 400; screen.height = 240;
        return screen;
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Screen screen, String name) throws Exception {
        Field field = Screen.class.getDeclaredField(name); field.setAccessible(true);
        return (List<Object>) field.get(screen);
    }
    private static Unsafe unsafe() {
        try { Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true); return (Unsafe) field.get(null); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static final class OtherScreen extends Screen {
        OtherScreen() { super((net.minecraft.client.Minecraft) null, null, Component.empty()); }
    }
    /** Explicit test double at the final navigation boundary; no world or Minecraft constructor runs. */
    private static final class RecordingWorldOptions extends WorldOptionsScreen {
        private Screen parent;
        private int closes;
        RecordingWorldOptions() { super(null, null); }
        @Override public void onClose() {
            closes++;
            try {
                Field current = Gui.class.getDeclaredField("screen"); current.setAccessible(true);
                current.set(Minecraft.getInstance().gui, parent);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }
}
