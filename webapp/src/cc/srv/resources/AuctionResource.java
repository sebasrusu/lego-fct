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
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@Path("/auction")
public class AuctionResource {
    private final CosmosDBLayer db = CosmosDBLayer.getInstance();
    private static final Logger LOG = Logger.getLogger(AuctionResource.class.getName());

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createAuction(Auction auction) {
        if (auction.getId() == null || auction.getId().isEmpty()) {
            auction.setId(UUID.randomUUID().toString());
        }
        LOG.info("createAuction payload: " + auction);
        AuctionDAO result = db.createAuction(new AuctionDAO(auction));
        LOG.info("created auction id=" + result.getId());
        return Response.created(URI.create("/auction/" + result.getId()))
                .entity(result.toAuction())
                .build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Auction> listAuctions(@QueryParam("legoSetId") String legoSetId,
                                      @QueryParam("st") @DefaultValue("0") int st,
                                      @QueryParam("len") @DefaultValue("20") int len) {
        LOG.info("listAuctions called legoSetId=" + legoSetId + " st=" + st + " len=" + len);
        if (legoSetId != null && !legoSetId.isEmpty()) {
            List<Auction> res = StreamSupport.stream(db.searchAuctionForLegoSet(legoSetId).spliterator(), false)
                    .map(AuctionDAO::toAuction).collect(Collectors.toList());
            LOG.info("listAuctions by legoSetId returned " + res.size());
            return res;
        }
        List<Auction> res = StreamSupport.stream(db.listAuctions(st, len).spliterator(), false)
                .map(AuctionDAO::toAuction).collect(Collectors.toList());
        LOG.info("listAuctions default returned " + res.size());
        return res;
    }

    // return open auctions (closeDate > now && not closed)
    @GET
    @Path("/open")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Auction> listOpenAuctions(@QueryParam("st") @DefaultValue("0") int st,
                                          @QueryParam("len") @DefaultValue("20") int len) {
        LOG.info("listOpenAuctions called st=" + st + " len=" + len);
        List<Auction> res = StreamSupport.stream(db.listOpenAuctions(st, len).spliterator(), false)
                .map(AuctionDAO::toAuction).collect(Collectors.toList());
        LOG.info("listOpenAuctions returned " + res.size());
        return res;
    }

    // return closed / past auctions (closed==true OR closeDate <= now)
    @GET
    @Path("/closed")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Auction> listClosedAuctions(@QueryParam("st") @DefaultValue("0") int st,
                                            @QueryParam("len") @DefaultValue("20") int len) {
        LOG.info("listClosedAuctions called st=" + st + " len=" + len);
        List<Auction> res = StreamSupport.stream(db.listClosedAuctions(st, len).spliterator(), false)
                .map(AuctionDAO::toAuction).collect(Collectors.toList());
        LOG.info("listClosedAuctions returned " + res.size());
        return res;
    }


    @POST
    @Path("/{id}/bid")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response placeBid(@PathParam("id") String auctionId, String body) {
        ObjectMapper mapper = new ObjectMapper();
        Bid bid = null;
        try {
            // log raw payload for debugging
            LOG.info("placeBid raw body for auctionId=" + auctionId + " -> " + body);

            JsonNode node = mapper.readTree(body);
            // try to parse into Bid (supports either numeric or string fields)
            bid = mapper.treeToValue(node, Bid.class);
        } catch (Exception e) {
            LOG.warning("placeBid: failed to parse bid JSON for auctionId=" + auctionId + " err=" + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid JSON payload for bid").build();
        }

        AuctionDAO auction = db.getAuction(auctionId);
        if (auction == null) {
            LOG.warning("placeBid: auction not found id=" + auctionId);
            return Response.status(Response.Status.NOT_FOUND).entity("Auction not found").build();
        }

        // log auction state to understand rejections
        LOG.info("placeBid for auctionId=" + auctionId + " auction.closed=" + auction.isClosed()
                + " closeDate=" + auction.getCloseDate() + " basePrice=" + auction.getBasePrice()
                + " existingBids=" + (auction.getBids() == null ? 0 : auction.getBids().size()));

        // Basic payload validation
        if (bid == null) {
            LOG.warning("placeBid: bid parsed null for auctionId=" + auctionId);
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid bid payload").build();
        }
        if (bid.getUserId() == null || bid.getUserId().isEmpty()) {
            LOG.warning("placeBid: missing userId in bid payload for auctionId=" + auctionId + " payload=" + body);
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing userId").build();
        }
        if (bid.getAmount() <= 0) {
            LOG.warning("placeBid: non-positive amount in bid payload for auctionId=" + auctionId + " payload=" + body);
            return Response.status(Response.Status.BAD_REQUEST).entity("Amount must be positive").build();
        }
        long now = System.currentTimeMillis();
        if (auction.isClosed() || auction.getCloseDate() <= now) {
            LOG.warning("placeBid: auction closed or expired for auctionId=" + auctionId + " now=" + now + " closeDate=" + auction.getCloseDate());
            return Response.status(Response.Status.BAD_REQUEST).entity("Auction closed or expired").build();
        }

        try {
            db.addBidToAuction(auctionId, bid);
        } catch (Exception e) {
            LOG.severe("placeBid: error adding bid to auctionId=" + auctionId + " : " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Failed to add bid").build();
        }

        LOG.info("placeBid: bid accepted for auctionId=" + auctionId + " bid=" + bid);
        return Response.ok().build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAuctionById(@PathParam("id") String auctionId) {
        LOG.info("getAuctionById called id=" + auctionId);
        AuctionDAO a = db.getAuction(auctionId);
        if (a == null) {
            LOG.info("getAuctionById: not found id=" + auctionId);
            return Response.status(Response.Status.NOT_FOUND).entity("Auction not found").build();
        }
        Auction out = new Auction(a.getId(), a.getLegoSetId(), a.getSellerId(), a.getBasePrice(), a.getCloseDate(), a.getBids(), a.isClosed());
        return Response.ok(out).build();
    }
}