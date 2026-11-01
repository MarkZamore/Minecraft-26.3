package proof.host;
import java.util.*;
import org.spongepowered.asm.service.*;
public final class OfflineProperties implements IGlobalPropertyService {
    private record Key(String name) implements IPropertyKey { }
    private final Map<IPropertyKey, Object> values = new HashMap<>();
    public IPropertyKey resolveKey(String name) { return new Key(name); }
    @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key) { return (T) values.get(key); }
    public void setProperty(IPropertyKey key, Object value) { values.put(key, value); }
    @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key, T fallback) { return (T) values.getOrDefault(key, fallback); }
    public String getPropertyString(IPropertyKey key, String fallback) { return String.valueOf(values.getOrDefault(key, fallback)); }
}
