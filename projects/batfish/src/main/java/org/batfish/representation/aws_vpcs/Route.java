package org.batfish.representation.aws_vpcs;

import java.io.Serializable;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import org.batfish.common.BatfishException;
import org.batfish.common.BatfishLogger;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.StaticRoute;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

public class Route implements Serializable {

  public enum State {
    ACTIVE,
    BLACKHOLE
  }

  public enum TargetType {
    Gateway,
    Instance,
    NetworkInterface,
    Unavailable,
    VpcPeeringConnection
  }

  public static final int DEFAULT_STATIC_ROUTE_ADMIN = 1;

  public static final int DEFAULT_STATIC_ROUTE_COST = 0;

  private static final long serialVersionUID = 1L;

  private Prefix _destinationCidrBlock;
  private State _state;
  private String _target;
  private TargetType _targetType;

  public Route(JSONObject jObj, BatfishLogger logger) throws JSONException {
    _destinationCidrBlock =
        new Prefix(jObj.getString(AwsVpcEntity.JSON_KEY_DESTINATION_CIDR_BLOCK));
    _state = State.valueOf(jObj.getString(AwsVpcEntity.JSON_KEY_STATE).toUpperCase());

    if (jObj.has(AwsVpcEntity.JSON_KEY_VPC_PEERING_CONNECTION_ID)) {
      _targetType = TargetType.VpcPeeringConnection;
      _target = jObj.getString(AwsVpcEntity.JSON_KEY_VPC_PEERING_CONNECTION_ID);
    } else if (jObj.has(AwsVpcEntity.JSON_KEY_GATEWAY_ID)) {
      _targetType = TargetType.Gateway;
      _target = jObj.getString(AwsVpcEntity.JSON_KEY_GATEWAY_ID);
    } else if (jObj.has(AwsVpcEntity.JSON_KEY_NETWORK_INTERFACE_ID)) {
      _targetType = TargetType.NetworkInterface;
      _target = jObj.getString(AwsVpcEntity.JSON_KEY_NETWORK_INTERFACE_ID);
    } else if (jObj.has(AwsVpcEntity.JSON_KEY_INSTANCE_ID)) {
      // NOTE: so far in practice this branch is never reached after moving
      // networkInterfaceId above it!
      _targetType = TargetType.Instance;
      _target = jObj.getString(AwsVpcEntity.JSON_KEY_INSTANCE_ID);
    } else if (_state == State.BLACKHOLE) {
      _targetType = TargetType.Unavailable;
      _target = null;
    } else {
      throw new JSONException("Target not found in route " + jObj);
    }
  }

  @Nullable
  public StaticRoute toStaticRoute(
      AwsVpcConfiguration awsVpcConfiguration,
      Ip vpcAddress,
      @Nullable Ip igwAddress,
      @Nullable Ip vgwAddress,
      Subnet subnet,
      Configuration subnetCfgNode) {
    // setting the common properties
    StaticRoute.Builder srBuilder =
        StaticRoute.builder()
            .setNetwork(_destinationCidrBlock)
            .setAdministrativeCost(DEFAULT_STATIC_ROUTE_ADMIN)
            .setTag(DEFAULT_STATIC_ROUTE_COST);

    if (_state == State.BLACKHOLE) {
      srBuilder.setNextHopInterface(Interface.NULL_INTERFACE_NAME);
    } else {
      switch (_targetType) {
        case Gateway:
          if (_target.equals("local")) {
            // send to the vpc router
            srBuilder.setNextHopIp(vpcAddress);
          } else {
            // send to the specified internet gateway
            // if it's not the igw or vgw set for this subnet (and VPC),
            // throw an
            // exception
            if (_target.equals(subnet.getInternetGatewayId())) {
              srBuilder.setNextHopIp(igwAddress);
            } else if (_target.equals(subnet.getVpnGatewayId())) {
              srBuilder.setNextHopIp(vgwAddress);
            } else {
              throw new BatfishException(
                  "Internet gateway \""
                      + _target
                      + "\" specified in this route not accessible from this subnet");
            }
          }
          break;

        case NetworkInterface:
          NetworkInterface networkInterface =
              awsVpcConfiguration.getNetworkInterfaces().get(_target);
          String networkInterfaceSubnetId = networkInterface.getSubnetId();
          if (networkInterfaceSubnetId.equals(subnet.getId())) {
            Set<Ip> networkInterfaceIps = new TreeSet<>();
            networkInterfaceIps.addAll(networkInterface.getIpAddressAssociations().keySet());
            Ip lowestIp = networkInterfaceIps.toArray(new Ip[] {})[0];
            if (!subnet.getCidrBlock().contains(lowestIp)) {
              throw new BatfishException(
                  "Ip of network interface specified in static route not in containing subnet");
            }
            srBuilder.setNextHopIp(lowestIp);
          } else {
            String networkInterfaceVpcId =
                awsVpcConfiguration.getSubnets().get(networkInterfaceSubnetId).getVpcId();
            String vpcId = subnet.getVpcId();
            if (!vpcId.equals(networkInterfaceVpcId)) {
              throw new BatfishException("Cannot peer with interface on different VPC");
            }
            // need to create a link between subnet on which route is created
            // and instance containing network interface
            String subnetIfaceName = _target;
            Prefix instanceLinkPrefix = awsVpcConfiguration.getNextGeneratedLinkSubnet();
            Prefix subnetIfacePrefix = instanceLinkPrefix;
            Interface subnetIface = new Interface(subnetIfaceName, subnetCfgNode);
            subnetCfgNode.getDefaultVrf().getInterfaces().put(subnetIfaceName, subnetIface);
            subnetIface.setPrefix(subnetIfacePrefix);
            subnetIface.getAllPrefixes().add(subnetIfacePrefix);

            // set up instance interface
            String instanceId = networkInterface.getAttachmentInstanceId();
            String instanceIfaceName = subnet.getId();
            Configuration instanceCfgNode =
                awsVpcConfiguration.getConfigurationNodes().get(instanceId);
            Prefix instanceIfacePrefix =
                new Prefix(
                    instanceLinkPrefix.getEndAddress(), instanceLinkPrefix.getPrefixLength());
            Interface instanceIface = new Interface(instanceIfaceName, instanceCfgNode);
            instanceCfgNode.getDefaultVrf().getInterfaces().put(instanceIfaceName, instanceIface);
            instanceIface.setPrefix(instanceIfacePrefix);
            instanceIface.getAllPrefixes().add(instanceIfacePrefix);
            Instance instance = awsVpcConfiguration.getInstances().get(instanceId);
            instanceIface.setIncomingFilter(instance.getInAcl());
            instanceIface.setOutgoingFilter(instance.getOutAcl());
            Ip nextHopIp = instanceIfacePrefix.getAddress();
            srBuilder.setNextHopIp(nextHopIp);
          }
          break;

        case VpcPeeringConnection:
          // create route for vpc peering connection
          String vpcPeeringConnectionid = _target;
          VpcPeeringConnection vpcPeeringConnection =
              awsVpcConfiguration.getVpcPeeringConnections().get(vpcPeeringConnectionid);
          String localVpcId = subnet.getVpcId();
          String accepterVpcId = vpcPeeringConnection.getAccepterVpcId();
          String requesterVpcId = vpcPeeringConnection.getRequesterVpcId();
          String remoteVpcId = localVpcId.equals(accepterVpcId) ? requesterVpcId : accepterVpcId;
          Configuration remoteVpcCfgNode =
              awsVpcConfiguration.getConfigurationNodes().get(remoteVpcId);
          if (remoteVpcCfgNode == null) {
            awsVpcConfiguration
                .getWarnings()
                .redFlag(
                    "VPC \""
                        + localVpcId
                        + "\" cannot peer with non-existent VPC: \""
                        + remoteVpcId
                        + "\"");
            return null;
          }

          // set up subnet interface if necessary
          String subnetIfaceName = remoteVpcId;
          String remoteVpcIfaceName = subnet.getId();
          Ip remoteVpcIfaceAddress;
          if (!subnetCfgNode.getDefaultVrf().getInterfaces().containsKey(subnetIfaceName)) {
            // create prefix on which subnet and remote vpc router will
            // connect
            Prefix peeringLinkPrefix = awsVpcConfiguration.getNextGeneratedLinkSubnet();
            Prefix subnetIfacePrefix = peeringLinkPrefix;
            Interface subnetIface = new Interface(subnetIfaceName, subnetCfgNode);
            subnetCfgNode.getDefaultVrf().getInterfaces().put(subnetIfaceName, subnetIface);
            subnetIface.setPrefix(subnetIfacePrefix);
            subnetIface.getAllPrefixes().add(subnetIfacePrefix);

            // set up remote vpc router interface
            Prefix remoteVpcIfacePrefix =
                new Prefix(peeringLinkPrefix.getEndAddress(), peeringLinkPrefix.getPrefixLength());
            Interface remoteVpcIface = new Interface(remoteVpcIfaceName, remoteVpcCfgNode);
            remoteVpcCfgNode
                .getDefaultVrf()
                .getInterfaces()
                .put(remoteVpcIfaceName, remoteVpcIface);
            remoteVpcIface.setPrefix(remoteVpcIfacePrefix);
            remoteVpcIface.getAllPrefixes().add(remoteVpcIfacePrefix);
          }
          // interface pair exists now, so just retrieve existing information
          remoteVpcIfaceAddress =
              remoteVpcCfgNode
                  .getDefaultVrf()
                  .getInterfaces()
                  .get(remoteVpcIfaceName)
                  .getPrefix()
                  .getAddress();

          // initialize static route on new link
          srBuilder.setNextHopIp(remoteVpcIfaceAddress);
          break;

        case Instance:
          // TODO: create route for instance
          awsVpcConfiguration
              .getWarnings()
              .redFlag(
                  "Skipping creating route to "
                      + _destinationCidrBlock
                      + " for instance: \""
                      + _target
                      + "\"");
          return null;

        default:
          throw new BatfishException("Unsupported target type: " + _targetType);
      }
    }
    return srBuilder.build();
  }
}
