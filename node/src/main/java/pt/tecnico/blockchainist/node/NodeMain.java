package pt.tecnico.blockchainist.node;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;

public class NodeMain {
    public static void main(String[] args) {
        // Check Args
        // Usage: mvn exec:java -Dexec.args="<port> <organization>
        // <sequencerHost>:<sequencerPort>"
        if (args.length < 3) {
            System.err.println("Argument(s) missing!");
            System.err.printf("Usage: java %s <port> <organization> <sequencerHost>:<sequencerPort>%n",
                    NodeMain.class.getName());
            return;
        }

        // Get the args
        final int port = Integer.parseInt(args[0]);
        final String organization = args[1];
        final String sequencerAddress = args[2];

        // Configure the service
        final BindableService impl = new NodeServiceImpl(organization, sequencerAddress);

        try {
            // Start the server
            Server server = ServerBuilder.forPort(port).addService(impl).build();
            server.start();
            System.out.println("Node Server started");

            // Close server
            server.awaitTermination();
        } catch (IOException e) {
            System.err.println("Server failed to start!");
            System.err.println("Error: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("Server Stopped!");
        }

    }
}
