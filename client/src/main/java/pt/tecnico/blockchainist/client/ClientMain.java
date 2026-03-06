package pt.tecnico.blockchainist.client;

import java.util.ArrayList;

import pt.tecnico.blockchainist.client.grpc.ClientNodeService;
import pt.tecnico.blockchainist.common.Utils;

/**
 * Main entry point for the blockchain client application.
 * This class initializes connections to blockchain nodes and starts the command processor
 * for user interaction.
 */
public class ClientMain {

    /**
     * Main method that parses command-line arguments and initializes the client.
     *
     * @param args command-line arguments specifying node connection details format host:port:organization
     */
    public static void main(String[] args) {

        System.out.println(ClientMain.class.getSimpleName());

        // check arguments
        if (args.length < 1) {
            System.err.println("Argument(s) missing!");
            printUsage();
            return;
        }

        // parse arguments
        ArrayList<ClientNodeService> nodes = new ArrayList<>(args.length);
        for (String arg : args) {
            String[] split = arg.split(":");
            if (split.length != 3) {
                System.err.println("Invalid argument: " + arg);
                printUsage();
                return;
            }
            String host = split[0];
            int port = -1;
            try {
                port = Utils.parsePort(split[1], "node");
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid port (" + split[1] + ") in argument: " + arg);
                printUsage();
                return;
            }
            String organization = split[2];

            nodes.add(new ClientNodeService(host, port, organization));
        }

        CommandProcessor processor = new CommandProcessor(nodes);
        processor.userInputLoop();
    }

    private static void printUsage() {
        System.err.println("Usage: mvn exec:java -Dexec.args=\"<host>:<port>:<organization> [<host>:<port>:<organization> ...]\"");
    }
}
