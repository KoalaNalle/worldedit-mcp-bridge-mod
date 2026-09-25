package com.aeronauticsmcp.weditmcpbridge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaletteSafetyTest {
  private PaletteSafety.Facts facts(boolean registered, boolean roundTrip, boolean noProperties,
      boolean full, boolean entity, boolean fluid, boolean falling, boolean ticks, boolean signal) {
    return new PaletteSafety.Facts(registered, roundTrip, noProperties, full, entity, fluid, falling, ticks, signal);
  }

  @Test
  void onlySimpleRegisteredInertFullDefaultsPass() {
    assertEquals(List.of(), PaletteSafety.reasons(facts(true, true, true, true, false, false, false, false, false)));
    List<PaletteSafety.Facts> invalid = List.of(
        facts(false, true, true, true, false, false, false, false, false),
        facts(true, false, true, true, false, false, false, false, false),
        facts(true, true, false, true, false, false, false, false, false),
        facts(true, true, true, false, false, false, false, false, false),
        facts(true, true, true, true, true, false, false, false, false),
        facts(true, true, true, true, false, true, false, false, false),
        facts(true, true, true, true, false, false, true, false, false),
        facts(true, true, true, true, false, false, false, true, false),
        facts(true, true, true, true, false, false, false, false, true));
    List<String> codes = List.of("registry_missing", "default_state_round_trip_failed",
        "block_properties_unsupported", "not_full_collision_block", "block_entity_unsupported",
        "fluid_unsupported", "gravity_unsupported", "random_ticks_unsupported", "signal_source_unsupported");
    for (int i = 0; i < invalid.size(); i++) assertEquals(List.of(codes.get(i)), PaletteSafety.reasons(invalid.get(i)));
  }

  @Test
  void metadataReportsEveryKnownUnsafeProperty() {
    assertEquals(8, PaletteSafety.reasons(facts(true, false, false, false, true, true, true, true, true)).size());
  }
}
