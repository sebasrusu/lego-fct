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
    public List<Auction> listAuctions(@QueryParam("legoSetId") String legoSetId, @QueryParam("recent") String recent, @QueryParam("st") @DefaultValue("0") int st, @QueryParam("len") @DefaultValue("20") int len) {
        if (legoSetId != null && !legoSetId.isEmpty()) {
            return StreamSupport.stream(db.searchAuctionForLegoSet(legoSetId).spliterator(), false)
                    .map(AuctionDAO::toAuction).collect(Collectors.toList());
        }
        if (recent != null) {
             return StreamSupport.stream(db.listAuctions(st, len).spliterator(), false)
                .map(AuctionDAO::toAuction).collect(Collectors.toList());
        }
        return StreamSupport.stream(db.listAuctions(st, len).spliterator(), false)
                .map(AuctionDAO::toAuction).collect(Collectors.toList());
    }

    @POST
    @Path("/{id}/bid")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response placeBid(@PathParam("id") String auctionId, Bid bid) {
        // Primeiro, obtemos o leilão para verificar se ele existe.
        AuctionDAO auction = db.getAuction(auctionId);
        
        // Se o leilão não for encontrado, retornamos um erro 404 Not Found.
        if (auction == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        // Se o leilão existir, adicionamos o lance. Esta chamada não retorna nada.
        db.addBidToAuction(auctionId, bid);
        
        // Retornamos uma resposta de sucesso.
        return Response.ok().build();
    }
}