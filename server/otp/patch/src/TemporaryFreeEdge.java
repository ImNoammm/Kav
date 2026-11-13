package org.opentripplanner.street.model.edge;

import org.opentripplanner.street.model.vertex.TemporaryVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.street.search.state.StateEditor;

/**
 * A temporary edge which links the origin / destination of a street search to the street graph.
 */
public class TemporaryFreeEdge extends FreeEdge implements TemporaryEdge {

  /**
   * Kav: how much farther this link's street is than the nearest street the same point is linked to, walked at the
   * request's pace and reluctance (VertexLinker joins a request's point to several streets on foot). Like the step onto
   * the nearest street, it is not part of the walk's distance.
   */
  private double extraMeters = 0;

  private TemporaryFreeEdge(TemporaryVertex from, Vertex to) {
    super((Vertex) from, to);
  }

  private TemporaryFreeEdge(Vertex from, TemporaryVertex to) {
    super(from, (Vertex) to);
  }

  public static TemporaryFreeEdge createTemporaryFreeEdge(TemporaryVertex from, Vertex to) {
    return connectToGraph(new TemporaryFreeEdge(from, to));
  }

  public static TemporaryFreeEdge createTemporaryFreeEdge(Vertex from, TemporaryVertex to) {
    return connectToGraph(new TemporaryFreeEdge(from, to));
  }

  public void setExtraMeters(double extraMeters) {
    this.extraMeters = extraMeters;
  }

  public double getExtraMeters() {
    return extraMeters;
  }

  @Override
  public State[] traverse(State s0) {
    StateEditor s1 = s0.edit(this);
    // a small cost is added to prevent other searches going through this temporary edge
    s1.incrementWeight(1);
    s1.setBackMode(null);
    if (extraMeters > 0 && !s0.currentMode().isDrivingIsh()) {
      var walk = s0.getRequest().walk();
      double seconds = extraMeters / walk.speed();
      s1.incrementTimeInMilliseconds((long) Math.ceil(1000.0 * seconds));
      s1.incrementWeight(seconds * walk.reluctance());
    }

    if (s0.isRentingVehicleFromStation() && s0.mayKeepRentedVehicleAtDestination()) {
      var rentalPreferences = s0.getRequest().rental(s0.getRequest().mode());
      if (rentalPreferences.allowArrivingInRentedVehicleAtDestination()) {
        s1.incrementWeight(
          rentalPreferences.arrivingInRentalVehicleAtDestinationCost().toSeconds()
        );
      }
    }

    return s1.makeStateArray();
  }
}
