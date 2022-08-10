package org.batfish.coordinator.resources;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.batfish.common.WorkItem;
import org.batfish.coordinator.Main;
import org.batfish.coordinator.QueuedWork;
import org.batfish.coordinator.WorkQueueMgr.QueueType;
import org.batfish.datamodel.pojo.WorkStatus;

/** Resource for servicing client API calls for work item(s) for a given network */
@ParametersAreNonnullByDefault
public final class WorkResource {

  public WorkResource(String network) {
    _network = network;
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public @Nonnull Response queueWork(WorkItem workItem, @Context UriInfo uriInfo) {
    QueuedWork existingWork = Main.getWorkMgr().getMatchingWork(workItem, QueueType.INCOMPLETE);
    if (existingWork != null) {
      throw new BadRequestException(
          String.format(
              "Supplied WorkID '%s' is a duplicate of existing WorkID: '%s'",
              workItem.getId(), existingWork.getId()));
    }
    Main.getWorkMgr().queueWork(workItem);
    return Response.created(
            uriInfo.getRequestUriBuilder().path(workItem.getId().toString()).build())
        .build();
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/{workid}")
  public @Nonnull WorkStatus getWorkStatus(@PathParam("workid") @Nonnull String workIdStr) {
    UUID workId;
    try {
      workId = UUID.fromString(workIdStr);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Work ID must be a valid UUID");
    }
    QueuedWork queuedWork = Main.getWorkMgr().getWork(workId);
    if (queuedWork == null) {
      // No work with requested ID.
      throw new NotFoundException();
    }
    if (!_network.equals(queuedWork.getWorkItem().getNetwork())) {
      // Work with requested ID not part of requested network, so request is not authorized.
      // Rather than leak info, just return 404.
      throw new NotFoundException();
    }
    return queuedWork.toWorkStatus();
  }

  private final @Nonnull String _network;
}
