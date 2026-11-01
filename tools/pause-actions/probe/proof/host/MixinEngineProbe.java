package proof.host;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

public final class MixinEngineProbe {
    private static final String TARGET = "net.minecraft.client.gui.components.Button";
    public static void main(String[] args) throws Exception {
        initializeFmlEnvironment();
        byte[] original = bytes(TARGET);
        String negative = args.length > 6 ? args[6] : null;
        if (negative != null) {
            ClassNode malformed = new ClassNode(); new ClassReader(original).accept(malformed, 0);
            var fields = malformed.fields.stream().filter(field -> field.name.equals("onPress")).toList();
            if (fields.size() != 1) throw new AssertionError("Native target field identity changed");
            if (negative.equals("missing-field")) malformed.fields.remove(fields.getFirst());
            else if (negative.equals("wrong-field-type")) fields.getFirst().desc = "Ljava/lang/Runnable;";
            else throw new AssertionError("Unknown negative input case");
            // Fault injection changes input only. The actual Mixin engine must reject it.
            ClassWriter writer = new ClassWriter(0); malformed.accept(writer); original = writer.toByteArray();
        }
        Object baseline = negative == null ? execute(original, false, args) : null;
        MixinBootstrap.init();
        if (!MixinService.getService().getName().equals("NativeButtonAccessorOffline")) throw new AssertionError("Unexpected Mixin host");
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("lanmc-pause-actions.mixins.json");
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        IMixinTransformer transformer = (IMixinTransformer) constructor.newInstance();
        byte[] transformed;
        try { transformed = transformer.transformClassBytes(TARGET, TARGET, original); }
        catch (Throwable failure) {
            if (negative == null) throw failure;
            StringBuilder reasons = new StringBuilder();
            for (Throwable current = failure; current != null; current = current.getCause()) reasons.append(current.getClass().getName()).append(':').append(current.getMessage()).append('\n');
            if (!reasons.toString().contains("onPress") || !reasons.toString().toLowerCase(Locale.ROOT).contains("accessor")) throw new AssertionError("Unexpected negative engine failure", failure);
            System.out.println("ACTUAL_MIXIN_NEGATIVE_REJECTED " + new com.google.gson.Gson().toJson(Map.of(
                    "case", negative, "engine", transformer.getClass().getName(), "status", "REJECTED", "failureType", failure.getClass().getName())));
            return;
        }
        if (negative != null) throw new AssertionError("Actual Mixin unexpectedly accepted malformed native field: " + negative);
        if (Arrays.equals(original, transformed)) throw new AssertionError("The actual Mixin engine did not transform native Button");
        ClassNode result = new ClassNode(); new ClassReader(transformed).accept(result, 0);
        if (!result.interfaces.contains("lanmc/pauseactions/mixin/ButtonActionAccessor")) throw new AssertionError("No generated accessor interface");
        int writes = 0, reads = 0;
        for (var method : result.methods) if (method.name.startsWith("lanmc$"))
            for (var insn : method.instructions) if (insn instanceof FieldInsnNode field && field.owner.equals(TARGET.replace('.', '/')) && field.name.equals("onPress")) {
                if (field.getOpcode() == Opcodes.PUTFIELD) writes++;
                if (field.getOpcode() == Opcodes.GETFIELD) reads++;
            }
        if (writes != 1 || reads != 1) throw new AssertionError("Expected actual generated native field getter/setter");
        Path output = Path.of(args[0]); Files.createDirectories(output);
        Files.write(output.resolve("Button.mixin-transformed.class"), transformed);
        var proof = new LinkedHashMap<String, Object>();
        proof.put("status", "PASS"); proof.put("actualMixinVersion", MixinEnvironment.getDefaultEnvironment().getVersion());
        proof.put("engine", transformer.getClass().getName()); proof.put("nativeClassOriginalBytes", original.length);
        proof.put("nativeClassOriginalSha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original)));
        proof.put("nativeClassTransformedBytes", transformed.length); proof.put("generatedNativeFieldReads", reads);
        proof.put("generatedNativeFieldWrites", writes); proof.put("baseline", baseline); proof.put("execution", execute(transformed, true, args));
        proof.put("fmlEnvironment", "Genuine FMLLoader CLIENT/PROD context constructor plus genuine empty LoadingModList; no discovery or game main");
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(proof);
        Files.writeString(output.resolve("proof.json"), json + "\n");
        System.out.println("ACTUAL_MIXIN_BUTTON_ACCESSOR_PASS " + new com.google.gson.Gson().toJson(proof));
    }
    private static void initializeFmlEnvironment() throws Exception {
        // The patched SharedConstants reads the genuine loader's client/production
        // environment. Invoke only its context constructor, never create()/discovery
        // or a client entry point, which would initialize platform/UI services.
        Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
        Class<?> dist = Class.forName("net.neoforged.api.distmarker.Dist");
        var constructor = loader.getDeclaredConstructor(ClassLoader.class, String[].class, dist, boolean.class, Path.class);
        constructor.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"}) Object client = Enum.valueOf((Class)dist, "CLIENT");
        Object context = constructor.newInstance(MixinEngineProbe.class.getClassLoader(), new String[]{"--fml.neoForgeVersion", "26.3.0.99", "--fml.mcVersion", "26.3"},
                client, true, Path.of(".").toAbsolutePath().normalize());
        Class<?> modList = Class.forName("net.neoforged.fml.loading.LoadingModList");
        Object emptyMods = modList.getMethod("of", List.class, List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
        var loadingMods = loader.getDeclaredField("loadingModList"); loadingMods.setAccessible(true); loadingMods.set(context, emptyMods);
    }
    private static Object execute(byte[] button, boolean transformed, String[] args) throws Exception {
        ClassLoader child = new ClassLoader(MixinEngineProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("net.minecraft.") && !name.startsWith("com.mojang.math.") && !name.startsWith("com.mojang.blaze3d.")
                        && !name.startsWith("com.mojang.realmsclient.") && !name.startsWith("lanmc.pauseactions.")
                        && !name.startsWith("net.neoforged.neoforge.") && !name.startsWith("proof.exec.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) {
                        byte[] payload;
                        try { payload = name.equals(TARGET) ? button : bytes(name); }
                        catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                        found = defineClass(name, payload, 0, payload.length);
                    }
                    if (resolve) resolveClass(found);
                    return found;
                }
            }
        };
        try { return child.loadClass("proof.exec.ExecutableProbe").getMethod("run", boolean.class, String[].class).invoke(null, transformed, args); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
    private static byte[] bytes(String name) throws IOException {
        try (var input = MixinEngineProbe.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input == null) throw new IOException("Native class unavailable: " + name);
            return input.readAllBytes();
        }
    }
}
