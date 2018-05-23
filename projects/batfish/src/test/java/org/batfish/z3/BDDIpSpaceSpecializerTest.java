package org.batfish.z3;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.google.common.collect.ImmutableMap;
import net.sf.javabdd.BDD;
import net.sf.javabdd.BDDFactory;
import net.sf.javabdd.JFactory;
import org.batfish.datamodel.AclIpSpace;
import org.batfish.datamodel.EmptyIpSpace;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.IpSpace;
import org.batfish.datamodel.IpWildcard;
import org.batfish.datamodel.IpWildcardSetIpSpace;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.UniverseIpSpace;
import org.batfish.symbolic.bdd.BDDInteger;
import org.batfish.symbolic.bdd.BDDOps;
import org.batfish.symbolic.bdd.IpSpaceToBDD;
import org.junit.Before;
import org.junit.Test;

public class BDDIpSpaceSpecializerTest {
  private BDDFactory _factory;
  private BDDOps _bddOps;
  private BDDInteger _ipAddrBdd;
  private IpSpaceToBDD _toBdd;

  @Before
  public void init() {
    _factory = JFactory.init(10000, 1000);
    _factory.disableReorder();
    _factory.setCacheRatio(64);
    _factory.setVarNum(32); // reserve 32 1-bit variables
    _bddOps = new BDDOps(_factory);
    _ipAddrBdd = BDDInteger.makeFromIndex(_factory, 32, 0, true);
    _toBdd = new IpSpaceToBDD(_factory, _ipAddrBdd);
  }

  private BDDIpSpaceSpecializer specializer(BDD specializeBDD) {
    return new BDDIpSpaceSpecializer(specializeBDD, _toBdd, ImmutableMap.of());
  }

  private BDDIpSpaceSpecializer specializer(IpSpace specializeIpSpace) {
    return new BDDIpSpaceSpecializer(_toBdd.visit(specializeIpSpace), _toBdd, ImmutableMap.of());
  }

  @Test
  public void testSpecializeIpIpSpace() {
    IpSpace ipSpace = new Ip("1.2.3.4").toIpSpace();
    assertThat(specializer(ipSpace).visit(ipSpace), equalTo(UniverseIpSpace.INSTANCE));
    assertThat(
        specializer(Prefix.parse("1.2.3.0/24").toIpSpace()).visit(ipSpace), equalTo(ipSpace));
    assertThat(
        specializer(new Ip("1.2.3.3").toIpSpace()).visit(ipSpace), equalTo(EmptyIpSpace.INSTANCE));
  }

  @Test
  public void testSpecializeIpWildcardIpSpace() {
    IpSpace ipSpace = new IpWildcard(new Ip("255.0.255.0"), new Ip("0.255.0.255")).toIpSpace();
    assertThat(specializer(ipSpace).visit(ipSpace), equalTo(UniverseIpSpace.INSTANCE));
    assertThat(
        specializer(Prefix.parse("255.0.0.0/8").toIpSpace()).visit(ipSpace), equalTo(ipSpace));
    assertThat(
        specializer(new Ip("1.2.3.4").toIpSpace()).visit(ipSpace), equalTo(EmptyIpSpace.INSTANCE));
  }

  @Test
  public void testAclIpSpace() {
    IpSpace ipSpace1 = Prefix.parse("1.0.0.0/8").toIpSpace();
    IpSpace ipSpace2 = Prefix.parse("2.0.0.0/8").toIpSpace();
    IpSpace ipSpace3 = Prefix.parse("1.1.0.0/16").toIpSpace();
    IpSpace ipSpace4 = Prefix.parse("2.2.0.0/16").toIpSpace();
    IpSpace ipSpace =
        AclIpSpace.builder()
            .thenPermitting(ipSpace1, ipSpace2)
            .thenRejecting(ipSpace3, ipSpace4)
            .build();
    /*
     * Unlike other cases, specializing an AclIpSpace to itself doesn't usually result in Universe.
     * The reason is that we specialize line-by-line.
     */
    assertThat(specializer(ipSpace).visit(ipSpace), equalTo(ipSpace));

    assertThat(
        specializer(Prefix.parse("0.0.0.0/7").toIpSpace()).visit(ipSpace),
        equalTo(AclIpSpace.builder().thenPermitting(ipSpace1).thenRejecting(ipSpace3).build()));

    assertThat(
        specializer(
                IpWildcardSetIpSpace.builder()
                    .including(IpWildcard.ANY)
                    .excluding(new IpWildcard(new Ip("0.1.0.0"), new Ip("255.0.255.255")))
                    .build())
            .visit(ipSpace),
        equalTo(
            AclIpSpace.builder()
                .thenPermitting(ipSpace1, ipSpace2)
                .thenRejecting(ipSpace4)
                .build()));
  }
}
