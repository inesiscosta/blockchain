package pt.tecnico.blockchainist.node;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import pt.tecnico.blockchainist.node.grpc.DelayInterceptor;
import pt.tecnico.blockchainist.node.grpc.NodeSequencerService;
import pt.tecnico.blockchainist.common.Utils;

import java.io.IOException;

/**
 * Main entry point for the blockchain node server.
 * This class initializes the node server, establishes connection to the
 * sequencer, and handles the server lifecycle.
 */
public class NodeMain {
    /**
     * Main method that starts the node server.
     * Expects arguments: port organization sequencerHost:sequencerPort
     *
     * @param args command-line arguments for node and sequencer configuration
     */
    public static void main(String[] args) {
        try {
            // Check Args
            if (args.length < 3) {
                System.err.println("[Error] Argument(s) missing!");
                printUsage();
                return;
            }

            // Get the Node args
            final int port = Utils.parsePort(args[0], "node");
            final String organization = args[1];

            // Get the Sequencer Args
            String[] sequencerSplit = args[2].split(":");
            if (sequencerSplit.length < 2) {
                System.err.println("[Error] Sequencer address must be host:port (e.g., localhost:3001)");
                return;
            }
            final String seqHost = sequencerSplit[0];
            final int seqPort = Utils.parsePort(sequencerSplit[1], "sequencer");

            // Configure Sequencer Connection
            NodeSequencerService sequencer = new NodeSequencerService(seqHost, seqPort);

            // Configure the service
            final BindableService impl = new NodeServiceImpl(organization, sequencer);

            // Configure Interceptor Service
            final ServerServiceDefinition implWithInterceptor = ServerInterceptors.intercept(impl,
                     new DelayInterceptor());

            // Start the server
            Server server = ServerBuilder.forPort(port).addService(implWithInterceptor).build();
            server.start();
            System.out.println("Node Server started");

            // Close Hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Shutdown] Received kill signal. Cleaning up...");

                // Shutdown gRPC
                server.shutdown();

                // Shutdown Sequencer
                sequencer.shutdown();

                System.out.println("[Shutdown] Network resources released. Goodbye!");
            }));

            // Wait
            server.awaitTermination();
        } catch (IOException e) {
            System.err.println("[Error] Server failed to start!");
            System.err.println("[Error] " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("Server Stopped!");
        } catch (IllegalArgumentException e) {
            System.err.println("[Error] " + e.getMessage());
        }

    }

    private static void printUsage() {
        System.err.printf("Usage: mvn exec:java -Dexec.args=\"<port> <organization> <sequencerHost>:<sequencerPort>\"");
    }
}
