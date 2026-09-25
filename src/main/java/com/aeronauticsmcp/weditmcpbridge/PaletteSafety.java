package com.aeronauticsmcp.weditmcpbridge;

import java.util.ArrayList;
import java.util.List;

/** Side-effect-free checks over facts observed from the loaded block registry. */
final class PaletteSafety {
  record Facts(boolean registered, boolean defaultRoundTrip, boolean noProperties,
      boolean fullCollision, boolean blockEntity, boolean fluid, boolean falling,
      boolean randomTicks, boolean signalSource) {}

  static List<String> reasons(Facts f) {
    List<String> reasons = new ArrayList<>();
    if (!f.registered) return List.of("registry_missing");
    if (!f.defaultRoundTrip) reasons.add("default_state_round_trip_failed");
    if (!f.noProperties) reasons.add("block_properties_unsupported");
    if (!f.fullCollision) reasons.add("not_full_collision_block");
    if (f.blockEntity) reasons.add("block_entity_unsupported");
    if (f.fluid) reasons.add("fluid_unsupported");
    if (f.falling) reasons.add("gravity_unsupported");
    if (f.randomTicks) reasons.add("random_ticks_unsupported");
    if (f.signalSource) reasons.add("signal_source_unsupported");
    return List.copyOf(reasons);
  }
}
