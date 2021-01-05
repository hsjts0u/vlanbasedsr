/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.vlanbasedsr;

import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;

import static org.onlab.util.Tools.get;
/**
  * Additional imports
  */
import com.google.common.collect.Lists;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;

import org.onlab.packet.Ethernet;
import org.onlab.packet.EthType;
import org.onlab.packet.IPv4;
import org.onlab.packet.VlanId;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.host.HostService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.HostId;
import org.onosproject.net.Host;
import org.onosproject.net.HostLocation;
import org.onosproject.net.PortNumber;
import org.onosproject.net.Path;
import org.onosproject.net.Link;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.topology.PathService;
import org.onosproject.net.topology.TopologyListener;
import org.onosproject.net.topology.TopologyService;

import java.util.List;
import java.util.Set;
import java.util.HashMap;

/**
 * Skeletal ONOS application component.
 */

@Component(immediate = true)
public class vlanbasedsr {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final DeviceConfigListener deviceCfgListener = new DeviceConfigListener();

    private final ConfigFactory deviceFactory =
        new ConfigFactory<ApplicationId, DeviceConf>(
            APP_SUBJECT_FACTORY, DeviceConf.class, "deviceConfig") {
              @Override
              public DeviceConf createConfig(){
                return new DeviceConf();
              }
            };

    private ApplicationId appId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.vlanbasedsr");
        cfgService.addListener(deviceCfgListener);
        cfgService.registerConfigFactory(deviceFactory);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.removeListener(deviceCfgListener);
        cfgService.unregisterConfigFactory(deviceFactory);
        log.info("Stopped");
    }

    private class DeviceConfigListener implements NetworkConfigListener{
        @Override
        public void event(NetworkConfigEvent event){
            if((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
              && event.configClass().equals(DeviceConf.class)){
                DeviceConf config = cfgService.getConfig(appId, DeviceConf.class);
                if(config != null){
                  log.info(config.deviceId());
                  log.info(config.subnetId());
                  log.info(config.subnetMask());
                  log.info(config.segmentId());
                  ruleInstaller(DeviceId.deviceId(config.deviceId()),
                                IpAddress.valueOf(config.subnetId()),
                                Integer.parseInt(config.subnetMask()),
                                VlanId.vlanId(config.segmentId()));
                }
            }
        }
    }

    void ruleInstaller(DeviceId deviceId, IpAddress ipAddress, int mask, VlanId segmentId){
        List<Device> srcDevices = Lists.newArrayList(deviceService.getDevices());
        for(Device srcDevice : srcDevices){
            Path path = pathFinder(srcDevice.id(), deviceId);
            if(path == null) continue;
            for(Link link : path.links()){
                firstHopPush(link.src(), ipAddress, mask, segmentId);
                interForward(link.src(), segmentId, mask);
            }
        }

        Set<Host> endHosts = hostService.getConnectedHosts(deviceId);
        if(endHosts.isEmpty()) log.info("no attached hosts");
        for(Host host : endHosts){
            Set<IpAddress> hostIps = host.ipAddresses();
            HostLocation location = host.location();
            for(IpAddress ip : hostIps){
                lastPopForward(ip, location, segmentId);
                subnetForward(ip, location);
            }
        }

    }

    Path pathFinder(DeviceId src, DeviceId dst){
        Set<Path> paths = topologyService.getPaths(topologyService.currentTopology(),
                                                   src,
                                                   dst);
        for(Path path : paths){
            return path;
        }
        return null;
    }

    void firstHopPush(ConnectPoint src, IpAddress ipAddress, int mask, VlanId segmentId){
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(Ethernet.TYPE_IPV4).matchIPDst(IpPrefix.valueOf(ipAddress, mask));
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                                                            .immediate()
                                                            .pushVlan()
                                                            .setVlanId(segmentId)
                                                            .setOutput(src.port())
                                                            .build();
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                                                                            .withSelector(selectorBuilder.build())
                                                                            .withTreatment(treatment)
                                                                            .fromApp(appId)
                                                                            .makePermanent()
                                                                            .withFlag(ForwardingObjective.Flag.EGRESS)
                                                                            .withPriority(50000+mask)
                                                                            .add();
        flowObjectiveService.forward(src.deviceId(), forwardingObjective);
    }

    void interForward(ConnectPoint src, VlanId segmentId, int mask){
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchVlanId(segmentId);
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                                                            .immediate()
                                                            .setOutput(src.port())
                                                            .build();
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                                                                            .withSelector(selectorBuilder.build())
                                                                            .withTreatment(treatment)
                                                                            .fromApp(appId)
                                                                            .makePermanent()
                                                                            .withFlag(ForwardingObjective.Flag.EGRESS)
                                                                            .withPriority(50040)
                                                                            .add();
        flowObjectiveService.forward(src.deviceId(), forwardingObjective);
    }

    void lastPopForward(IpAddress ip, HostLocation location, VlanId segmentId){
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchVlanId(segmentId)
                       .matchEthType(Ethernet.TYPE_IPV4).matchIPDst(IpPrefix.valueOf(ip, 32));
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                                                            .immediate()
                                                            .popVlan()
                                                            .setOutput(location.port())
                                                            .build();
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                                                                            .withSelector(selectorBuilder.build())
                                                                            .withTreatment(treatment)
                                                                            .fromApp(appId)
                                                                            .makePermanent()
                                                                            .withFlag(ForwardingObjective.Flag.EGRESS)
                                                                            .withPriority(50033)
                                                                            .add();
        flowObjectiveService.forward(location.deviceId(), forwardingObjective);
    }

    void subnetForward(IpAddress ip, HostLocation location){
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(Ethernet.TYPE_IPV4).matchIPDst(IpPrefix.valueOf(ip, 32));
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                                                            .immediate()
                                                            .setOutput(location.port())
                                                            .build();
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                                                                            .withSelector(selectorBuilder.build())
                                                                            .withTreatment(treatment)
                                                                            .fromApp(appId)
                                                                            .makePermanent()
                                                                            .withFlag(ForwardingObjective.Flag.EGRESS)
                                                                            .withPriority(50032)
                                                                            .add();
        flowObjectiveService.forward(location.deviceId(), forwardingObjective);
    }

}
