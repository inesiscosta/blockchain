package pt.tecnico.blockchainist.sequencer;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import pt.tecnico.blockchainist.common.Utils;
import pt.tecnico.blockchainist.sequencer.grpc.SignatureTrailerInterceptor;

import java.io.IOException;

/**
 * Main entry point for the blockchain sequencer server.
 * The sequencer is responsible for ordering transactions in the blockchain
 * network.
 */
public class SequencerMain {
    /**
     * Main method that starts the sequencer server.
     *
     * @param args command-line arguments with the port number
     */
    public static void main(String[] args) {
        try {
            // Check Args
            if (args.length < 1) {
                System.err.println("[Error] Argument(s) missing!");
                printUsage();
                return;
            }

            // Get the Sequencer args
            final int port = Utils.parsePort(args[0], "sequencer");
            final int blockTransactions = args.length > 1 ? Utils.parsePositiveInt(args[1], "block transactions") : 4;
            final int blockTime = args.length > 2 ? Utils.parsePositiveInt(args[2], "block time") : 5;

            // Configure the service
            final BindableService impl = new SequencerServiceImpl(blockTransactions, blockTime);

            // Configure Interceptor Service
            final ServerServiceDefinition implWithInterceptor = ServerInterceptors.intercept(impl,
                    new SignatureTrailerInterceptor());

            // Start the server
            Server server = ServerBuilder.forPort(port).addService(implWithInterceptor).build();
            server.start();
            System.out.println("Sequencer Server started");

            // Close Hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Shutdown] Received kill signal. Cleaning up...");

                // Shutdown gRPC
                server.shutdown();

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
        System.err.printf("Usage: mvn exec:java -Dexec.args=\"<port> [<blockTransactions>] [<blockTime>]\"");
    }
}
