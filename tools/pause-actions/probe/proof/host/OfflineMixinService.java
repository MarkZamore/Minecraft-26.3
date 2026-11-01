package proof.host;

import java.io.*;
import java.net.URL;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.*;

/** Headless class-byte host only. The unchanged Sponge Mixin engine performs the transformation. */
public final class OfflineMixinService extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider, IClassTracker {
    private final IContainerHandle container = new ContainerHandleVirtual("minecraft263-button-accessor-proof");
    public String getName() { return "NativeButtonAccessorOffline"; }
    public boolean isValid() { return true; }
    public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }
    public IClassProvider getClassProvider() { return this; }
    public IClassBytecodeProvider getBytecodeProvider() { return this; }
    public IClassTracker getClassTracker() { return this; }
    public ITransformerProvider getTransformerProvider() { return null; }
    public IMixinAuditTrail getAuditTrail() { return null; }
    public IFeatureValidator getFeatureValidator() { return null; }
    public IAdviceProvider getAdviceProvider() { return null; }
    public Collection<String> getPlatformAgents() { return List.of(); }
    public IContainerHandle getPrimaryContainer() { return container; }
    public InputStream getResourceAsStream(String name) { return getClass().getClassLoader().getResourceAsStream(name); }
    public URL[] getClassPath() { return new URL[0]; }
    public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException { return Class.forName(name, initialize, getClass().getClassLoader()); }
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException { return findClass(name, initialize); }
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException { return getClassNode(name, false); }
    public ClassNode getClassNode(String name, boolean transform) throws ClassNotFoundException, IOException { return getClassNode(name, transform, ClassReader.EXPAND_FRAMES); }
    public ClassNode getClassNode(String name, boolean transform, int flags) throws ClassNotFoundException, IOException {
        try (InputStream stream = getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) throw new ClassNotFoundException(name);
            ClassNode node = new ClassNode(); new ClassReader(stream).accept(node, flags); return node;
        }
    }
    public void registerInvalidClass(String name) { }
    public boolean isClassLoaded(String name) { return false; }
    public String getClassRestrictions(String name) { return ""; }
}
