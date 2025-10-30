package cc.srv;
import cc.srv.resources.*;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;


public class MainApplication extends Application {
    private final Set<Class<?>> resources = new HashSet<>();

    public MainApplication() {
        // Adiciona todas as classes de Resource para serem registadas pelo JAX-RS
        resources.add(ControlResource.class);
        resources.add(MediaResource.class);
        resources.add(UserResource.class);
        resources.add(LegoSetResource.class);
        resources.add(AuctionResource.class);
        resources.add(AuthResource.class);
    }

    @Override
    public Set<Class<?>> getClasses() {
        return resources;
    }
}