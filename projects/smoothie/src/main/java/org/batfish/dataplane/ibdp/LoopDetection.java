package org.batfish.dataplane.ibdp;

import com.google.common.collect.ImmutableSet;
import net.sf.javabdd.BDD;
import net.sf.javabdd.BDDFactory;
import net.sf.javabdd.JFactory;
import org.batfish.bddreachability.BDDLoopDetectionAnalysis;
import org.batfish.bddreachability.BDDReachabilityAnalysisFactory;
import org.batfish.bddreachability.IpsRoutedOutInterfacesFactory;
import org.batfish.common.bdd.BDDPacket;
import org.batfish.common.plugin.IBatfish;
import org.batfish.common.plugin.TracerouteEngine;
import org.batfish.common.topology.IpOwners;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.DataPlane;
import org.batfish.datamodel.Flow;
import org.batfish.datamodel.Topology;
import org.batfish.datamodel.flow.Trace;
import org.batfish.dataplane.TracerouteEngineImpl;
import org.batfish.referencelibrary.ReferenceBook;
import org.batfish.role.NodeRoleDimension;
import org.batfish.specifier.*;
import org.batfish.symbolic.IngressLocation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

import static org.batfish.specifier.LocationInfoUtils.computeLocationInfo;

public class LoopDetection {
  private final BDDFactory factory;

  public LoopDetection() {
    this.factory = BDDPacket.defaultFactory(JFactory::init);
  }

  public Set<Flow> bddLoopDetection(
      @Nonnull IBatfish batfish,
      DataPlane dataPlane,
      Map<String, Configuration> configs,
      IpOwners initialIpOwners) {
    BDDPacket pkt = new BDDPacket(factory);
    boolean ignoreFilters = false;
    BDDReachabilityAnalysisFactory bddReachabilityAnalysisFactory =
        new BDDReachabilityAnalysisFactory(
            pkt,
            configs,
            dataPlane.getForwardingAnalysis(),
            new IpsRoutedOutInterfacesFactory(dataPlane.getFibs()),
            ignoreFilters,
            false);
    BDDLoopDetectionAnalysis analysis =
        bddReachabilityAnalysisFactory.bddLoopDetectionAnalysis(
            getAllSourcesInferFromLocationIpSpaceAssignment(batfish, configs, initialIpOwners));
    Map<IngressLocation, BDD> loopBDDs = analysis.detectLoops();

    return loopBDDs.entrySet().stream()
        .map(
            entry ->
                pkt.getFlow(entry.getValue())
                    .map(
                        fb -> {
                          IngressLocation loc = entry.getKey();
                          fb.setIngressNode(loc.getNode());
                          switch (loc.getType()) {
                            case INTERFACE_LINK -> fb.setIngressInterface(loc.getInterface());
                            case VRF -> fb.setIngressVrf(loc.getVrf());
                          }
                          return fb.build();
                        }))
        .flatMap(Optional::stream)
        .collect(ImmutableSet.toImmutableSet());
  }

  public static @Nonnull IpSpaceAssignment getAllSourcesInferFromLocationIpSpaceAssignment(
      @Nonnull IBatfish batfish,
      @Nonnull Map<String, Configuration> configs,
      IpOwners initialIpOwners) {
    SpecifierContextImpl specifierContext =
        new SpecifierContextImpl(batfish, configs, initialIpOwners);
    Set<Location> locations =
        new UnionLocationSpecifier(
                AllInterfacesLocationSpecifier.INSTANCE,
                AllInterfaceLinksLocationSpecifier.INSTANCE)
            .resolve(specifierContext);
    return InferFromLocationIpSpaceAssignmentSpecifier.INSTANCE.resolve(
        locations, specifierContext);
  }

  public static class SpecifierContextImpl implements SpecifierContext {
    private final @Nonnull IBatfish _batfish;
    private final @Nonnull Map<String, Configuration> _configs;
    private final Map<Location, LocationInfo> _locationInfo;

    public SpecifierContextImpl(
        @Nonnull IBatfish batfish,
        @Nonnull Map<String, Configuration> configs,
        IpOwners initialIpOwners) {
      this._batfish = batfish;
      this._configs = configs;
      this._locationInfo = computeLocationInfo(initialIpOwners, configs);
    }

    @Nonnull
    @Override
    public Map<String, Configuration> getConfigs() {
      return _configs;
    }

    @Override
    public Optional<ReferenceBook> getReferenceBook(String bookName) {
      return _batfish.getReferenceLibraryData().getReferenceBook(bookName);
    }

    @Nonnull
    @Override
    public Optional<NodeRoleDimension> getNodeRoleDimension(@Nullable String dimension) {
      return _batfish.getNodeRoleDimension(dimension);
    }

    @Override
    public LocationInfo getLocationInfo(Location location) {
      return _locationInfo.getOrDefault(location, LocationInfo.NOTHING);
    }

    @Override
    public Map<Location, LocationInfo> getLocationInfo() {
      return _locationInfo;
    }
  }

  public static SortedMap<Flow, List<Trace>> buildTraces(
      DataPlane dataPlane,
      Topology layer3Topology,
      Map<String, Configuration> configs,
      Set<Flow> flows) {
    TracerouteEngine trEngine = new TracerouteEngineImpl(dataPlane, layer3Topology, configs);
    return trEngine.computeTraces(flows, false);
  }
}
