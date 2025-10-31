package cc.srv.resources;

import cc.data.auction.Auction;
import cc.data.auction.AuctionDAO;
import cc.data.bid.Bid;
import cc.db.CosmosDBLayer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Path("/auction")
public class AuctionResource {
    private final CosmosDBLayer db = CosmosDBLayer.getInstance();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createAuction(Auction auction) {
        if (auction.getId() == null || auction.getId().isEmpty()) {
            auction.setId(UUID.randomUUID().toString());
        }
        AuctionDAO result = db.createAuction(new AuctionDAO(auction));
        return Response.created(URI.create("/auction/" + result.getId()))
                .entity(result.toAuction())
                .build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Auction> listAuctions(@QueryParam("legoSetId") String legoSetId) {
        if (legoSetId != null && !legoSetId.isEmpty()) {
            return StreamSupport.stream(db.searchAuctionForLegoSet(legoSetId).spliterator(), false)
                    .map(AuctionDAO::toAuction).collect(Collectors.toList());
        }
        return StreamSupport.stream(db.listAuctions().spliterator(), false)
                .map(AuctionDAO::toAuction).collect(Collectors.toList());
    }

    @POST
    @Path("/{id}/bid")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response placeBid(@PathParam("id") String auctionId, Bid bid) {
        AuctionDAO auction = db.getAuction(auctionId);
        if (auction == null || auction.getCloseDate() < System.currentTimeMillis()) {
            return Response.status(Response.Status.FORBIDDEN).entity("\"Leilão não existe ou já fechou.\"").build();
        }
        db.addBidToAuction(auctionId, bid);
        return Response.ok().build();
    }
}