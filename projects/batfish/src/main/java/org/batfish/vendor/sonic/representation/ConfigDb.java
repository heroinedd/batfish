package org.batfish.vendor.sonic.representation;

import static com.google.common.base.MoreObjects.firstNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.batfish.common.Warnings;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.datamodel.ConcreteInterfaceAddress;
import org.batfish.datamodel.Prefix6;

/**
 * Represents ConfigDb for a one Sonic node.
 *
 * <p>See https://github.com/Azure/SONiC/wiki/Configuration
 */
public class ConfigDb implements Serializable {

  public @Nonnull Map<String, DeviceMetadata> getDeviceMetadata() {
    return _deviceMetadata;
  }

  public @Nonnull Map<String, L3Interface> getInterfaces() {
    return _interfaces;
  }

  public @Nonnull Map<String, L3Interface> getLoopbacks() {
    return _loopbacks;
  }

  public @Nonnull Set<String> getNtpServers() {
    return _ntpServers;
  }

  public @Nonnull Map<String, Port> getMgmtPorts() {
    return _mgmtPorts;
  }

  public @Nonnull Map<String, L3Interface> getMgmtInterfaces() {
    return _mgmtInterfaces;
  }

  public @Nonnull Map<String, MgmtVrf> getMgmtVrfs() {
    return _mgmtVrfs;
  }

  public @Nonnull Map<String, Port> getPorts() {
    return _ports;
  }

  public @Nonnull Set<String> getSyslogServers() {
    return _syslogServers;
  }

  public @Nonnull Map<String, Vlan> getVlans() {
    return _vlans;
  }

  public @Nonnull Map<String, L3Interface> getVlanInterfaces() {
    return _vlanInterfaces;
  }

  public @Nonnull Map<String, VlanMember> getVlanMembers() {
    return _vlanMembers;
  }

  private static final String PROP_DEVICE_METADATA = "DEVICE_METADATA";
  private static final String PROP_INTERFACE = "INTERFACE";
  private static final String PROP_LOOPBACK = "LOOPBACK";
  private static final String PROP_MGMT_INTERFACE = "MGMT_INTERFACE";
  private static final String PROP_MGMT_PORT = "MGMT_PORT";
  private static final String PROP_MGMT_VRF_CONFIG = "MGMT_VRF_CONFIG";
  private static final String PROP_PORT = "PORT";
  private static final String PROP_NTP_SERVER = "NTP_SERVER";
  private static final String PROP_SYSLOG_SERVER = "SYSLOG_SERVER";
  private static final String PROP_VLAN = "VLAN";
  private static final String PROP_VLAN_INTERFACE = "VLAN_INTERFACE";
  private static final String PROP_VLAN_MEMBER = "VLAN_MEMBER";

  /** Properties that are knowingly ignored and we won't warn the user about ignoring them */
  public static final Set<String> IGNORED_PROPERTIES =
      ImmutableSet.of("BUFFER_QUEUE", "DSCP_TO_TC_MAP", "ZTP");

  private final @Nonnull Map<String, DeviceMetadata> _deviceMetadata;
  private final @Nonnull Map<String, L3Interface> _interfaces;
  private final @Nonnull Map<String, L3Interface> _loopbacks;
  private final @Nonnull Map<String, L3Interface> _mgmtInterfaces;
  private final @Nonnull Map<String, Port> _mgmtPorts;
  private final @Nonnull Map<String, MgmtVrf> _mgmtVrfs;
  private final @Nonnull Set<String> _ntpServers;
  private final @Nonnull Map<String, Port> _ports;
  private final @Nonnull Set<String> _syslogServers;
  private final @Nonnull Map<String, Vlan> _vlans;
  private final @Nonnull Map<String, L3Interface> _vlanInterfaces;
  private final @Nonnull Map<String, VlanMember> _vlanMembers;

  private ConfigDb(
      Map<String, DeviceMetadata> deviceMetadata,
      Map<String, L3Interface> interfaces,
      Map<String, L3Interface> loopbacks,
      Map<String, L3Interface> mgmtInterfaces,
      Map<String, Port> mgmtPorts,
      Map<String, MgmtVrf> mgmtVrfs,
      Set<String> ntpServers,
      Map<String, Port> ports,
      Set<String> syslogServers,
      Map<String, Vlan> vlans,
      Map<String, L3Interface> vlanInterfaces,
      Map<String, VlanMember> vlanMembers) {
    _deviceMetadata = deviceMetadata;
    _interfaces = interfaces;
    _loopbacks = loopbacks;
    _mgmtInterfaces = mgmtInterfaces;
    _mgmtPorts = mgmtPorts;
    _mgmtVrfs = mgmtVrfs;
    _ntpServers = ntpServers;
    _ports = ports;
    _syslogServers = syslogServers;
    _vlans = vlans;
    _vlanInterfaces = vlanInterfaces;
    _vlanMembers = vlanMembers;
  }

  /**
   * Converts configdb's interface multi-level key encoding for interfaces, where keys are
   * "Ethernet", "Ethernet|1.1.1.1", to a map of interfaces.
   *
   * <p>In this encoding, the same interface may appear as key multiple times: by itself, with a v4
   * address, or with a v6 address.
   */
  @VisibleForTesting
  static Map<String, L3Interface> createInterfaces(Set<String> interfaceKeys) {
    Map<String, L3Interface> interfaces = new HashMap<>();
    for (String key : interfaceKeys) {
      String[] parts = key.split("\\|", 2);
      interfaces.computeIfAbsent(parts[0], i -> new L3Interface(null));
      if (parts.length == 2) {
        try {
          // if the interface appears with a v4 address, overwrite with that version
          ConcreteInterfaceAddress v4Address = ConcreteInterfaceAddress.parse(parts[1]);
          interfaces.put(parts[0], new L3Interface(v4Address));
        } catch (IllegalArgumentException e) {
          Prefix6.parse(parts[1]); // try to parse as v6; Will throw an exception upon failure
        }
      }
    }
    return ImmutableMap.copyOf(interfaces);
  }

  public @Nonnull Optional<String> getHostname() {
    return Optional.ofNullable(_deviceMetadata.get("localhost"))
        .flatMap(DeviceMetadata::getHostname)
        .map(String::toLowerCase);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Map<String, DeviceMetadata> _deviceMetadata;
    private Map<String, L3Interface> _interfaces;
    private Map<String, L3Interface> _loopbacks;
    private Map<String, L3Interface> _mgmtInterfaces;
    private Map<String, Port> _mgmtPorts;
    private Map<String, MgmtVrf> _mgmtVrfs;
    private Set<String> _ntpServers;
    private Map<String, Port> _ports;
    private Set<String> _syslogServers;
    private Map<String, Vlan> _vlans;
    private Map<String, L3Interface> _vlanInterfaces;
    private Map<String, VlanMember> _vlanMembers;

    private Builder() {}

    public @Nonnull Builder setDeviceMetadata(
        @Nullable Map<String, DeviceMetadata> deviceMetadata) {
      this._deviceMetadata = deviceMetadata;
      return this;
    }

    public @Nonnull Builder setInterfaces(@Nullable Map<String, L3Interface> interfaces) {
      this._interfaces = interfaces;
      return this;
    }

    public @Nonnull Builder setLoopbacks(@Nullable Map<String, L3Interface> loopbacks) {
      this._loopbacks = loopbacks;
      return this;
    }

    public @Nonnull Builder setMgmtInterfaces(@Nullable Map<String, L3Interface> mgmtInterfaces) {
      this._mgmtInterfaces = mgmtInterfaces;
      return this;
    }

    public @Nonnull Builder setMgmtPorts(@Nullable Map<String, Port> mgmtPorts) {
      this._mgmtPorts = mgmtPorts;
      return this;
    }

    public @Nonnull Builder setMgmtVrfs(@Nullable Map<String, MgmtVrf> mgmtVrfs) {
      this._mgmtVrfs = mgmtVrfs;
      return this;
    }

    public @Nonnull Builder setNtpServers(@Nullable Set<String> ntpServers) {
      this._ntpServers = ntpServers;
      return this;
    }

    public @Nonnull Builder setPorts(@Nullable Map<String, Port> ports) {
      this._ports = ports;
      return this;
    }

    public @Nonnull Builder setSyslogServers(@Nullable Set<String> syslogServers) {
      this._syslogServers = syslogServers;
      return this;
    }

    public @Nonnull Builder setVlans(@Nullable Map<String, Vlan> vlans) {
      this._vlans = vlans;
      return this;
    }

    public @Nonnull Builder setVlanInterfaces(@Nullable Map<String, L3Interface> vlanInterfaces) {
      this._vlanInterfaces = vlanInterfaces;
      return this;
    }

    public @Nonnull Builder setVlanMembers(@Nullable Map<String, VlanMember> vlanMembers) {
      this._vlanMembers = vlanMembers;
      return this;
    }

    public @Nonnull ConfigDb build() {
      return new ConfigDb(
          ImmutableMap.copyOf(firstNonNull(_deviceMetadata, ImmutableMap.of())),
          ImmutableMap.copyOf(firstNonNull(_interfaces, ImmutableMap.of())),
          ImmutableMap.copyOf(firstNonNull(_loopbacks, ImmutableMap.of())),
          ImmutableMap.copyOf(firstNonNull(_mgmtInterfaces, ImmutableMap.of())),
          ImmutableMap.copyOf(firstNonNull(_mgmtPorts, ImmutableMap.of())),
          ImmutableMap.copyOf(firstNonNull(_mgmtVrfs, ImmutableMap.of())),
          ImmutableSet.copyOf(firstNonNull(_ntpServers, ImmutableSet.of())),
          ImmutableMap.copyOf(firstNonNull(_ports, ImmutableMap.of())),
          ImmutableSet.copyOf(firstNonNull(_syslogServers, ImmutableSet.of())),
          ImmutableMap.copyOf(firstNonNull(_vlans, ImmutableMap.of())),
          ImmutableMap.copyOf(firstNonNull(_vlanInterfaces, ImmutableMap.of())),
          ImmutableMap.copyOf(firstNonNull(_vlanMembers, ImmutableMap.of())));
    }
  }

  public static ConfigDb deserialize(String configDbText, Warnings warnings)
      throws JsonProcessingException {
    return BatfishObjectMapper.mapper()
        .copy() // clone because the deserializer has Warnings object that must not be shared
        .registerModule(
            new SimpleModule().addDeserializer(ConfigDb.class, new Deserializer(warnings)))
        .readValue(configDbText, ConfigDb.class);
  }

  private static final class Deserializer extends StdDeserializer<ConfigDb> {

    private @Nonnull final Warnings _warnings;

    public Deserializer(Warnings warnings) {
      super(ConfigDb.class);
      _warnings = warnings;
    }

    @Override
    public ConfigDb deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      ConfigDb.Builder configDb = ConfigDb.builder();
      TreeNode tree = p.readValueAsTree();
      Iterator<String> fieldIterator = tree.fieldNames();
      // this mapper is used to convert the child trees. by default, it will not ignore unknown keys
      // inside children. the child classes may choose to ignore keys via Jackson annotation.
      ObjectMapper mapper = BatfishObjectMapper.mapper();
      while (fieldIterator.hasNext()) {
        String field = fieldIterator.next();
        TreeNode value = tree.get(field);
        switch (field) {
          case PROP_DEVICE_METADATA:
            configDb.setDeviceMetadata(
                mapper.convertValue(value, new TypeReference<Map<String, DeviceMetadata>>() {}));
            break;
          case PROP_INTERFACE:
            configDb.setInterfaces(
                createInterfaces(
                    mapper
                        .convertValue(value, new TypeReference<Map<String, Object>>() {})
                        .keySet()));
            break;
          case PROP_LOOPBACK:
            configDb.setLoopbacks(
                createInterfaces(
                    mapper
                        .convertValue(value, new TypeReference<Map<String, Object>>() {})
                        .keySet()));
            break;
          case PROP_MGMT_INTERFACE:
            {
              Map<String, Map<String, Object>> mgmtInterfaceMap =
                  mapper.convertValue(
                      value, new TypeReference<Map<String, Map<String, Object>>>() {});
              configDb.setMgmtInterfaces(createInterfaces(mgmtInterfaceMap.keySet()));
              Set<String> innerProperties =
                  mgmtInterfaceMap.values().stream()
                      .flatMap(map -> map.keySet().stream())
                      .collect(ImmutableSet.toImmutableSet());
              innerProperties.forEach(
                  key ->
                      _warnings.unimplemented(
                          String.format("Unimplemented MGMT_INTERFACE property '%s'", key)));
              break;
            }
          case PROP_MGMT_PORT:
            configDb.setMgmtPorts(
                mapper.convertValue(value, new TypeReference<Map<String, Port>>() {}));
            break;
          case PROP_MGMT_VRF_CONFIG:
            configDb.setMgmtVrfs(
                mapper.convertValue(value, new TypeReference<Map<String, MgmtVrf>>() {}));
            break;
          case PROP_NTP_SERVER:
            configDb.setNtpServers(
                mapper.convertValue(value, new TypeReference<Map<String, Object>>() {}).keySet());
            break;
          case PROP_PORT:
            configDb.setPorts(
                mapper.convertValue(value, new TypeReference<Map<String, Port>>() {}));
            break;
          case PROP_SYSLOG_SERVER:
            configDb.setSyslogServers(
                mapper.convertValue(value, new TypeReference<Map<String, Object>>() {}).keySet());
            break;
          case PROP_VLAN:
            configDb.setVlans(
                mapper.convertValue(value, new TypeReference<Map<String, Vlan>>() {}));
            break;
          case PROP_VLAN_INTERFACE:
            configDb.setVlanInterfaces(
                createInterfaces(
                    mapper
                        .convertValue(value, new TypeReference<Map<String, Object>>() {})
                        .keySet()));
            break;
          case PROP_VLAN_MEMBER:
            configDb.setVlanMembers(
                mapper.convertValue(value, new TypeReference<Map<String, VlanMember>>() {}));
            break;
          default:
            if (!IGNORED_PROPERTIES.contains(field)) {
              _warnings.unimplemented(String.format("Unimplemented configdb table '%s'", field));
            }
        }
      }
      return configDb.build();
    }
  }
}
