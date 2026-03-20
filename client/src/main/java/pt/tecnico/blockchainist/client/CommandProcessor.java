package pt.tecnico.blockchainist.client;

import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.google.protobuf.Message;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import pt.tecnico.blockchainist.client.grpc.ClientNodeService;
import pt.tecnico.blockchainist.client.grpc.MessageObserver;
import pt.tecnico.blockchainist.common.Utils;
import pt.tecnico.blockchainist.contract.ReadBalanceResponse;

/**
 * Processes user commands for interacting with the blockchain.
 * This class implements a command-line interface for executing blockchain
 * operations
 * such as wallet creation, deletion, transfers, and balance queries.
 */
public class CommandProcessor {

    private static final String SPACE = " ";
    private static final String CREATE_BLOCKING = "C";
    private static final String CREATE_ASYNC = "c";
    private static final String DELETE_BLOCKING = "E";
    private static final String DELETE_ASYNC = "e";
    private static final String BALANCE = "S";
    private static final String TRANSFER_BLOCKING = "T";
    private static final String TRANSFER_ASYNC = "t";
    private static final String DEBUG_BLOCKCHAIN_STATE = "B";
    private static final String PAUSE = "P";
    private static final String EXIT = "X";

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");

    private final AtomicLong commandCounter = new AtomicLong(0);
    private final ArrayList<ClientNodeService> nodes;

    /**
     * Constructs a new CommandProcessor with a list of node connections.
     *
     * @param nodes the list of blockchain node services to interact with
     */
    public CommandProcessor(ArrayList<ClientNodeService> nodes) {
        this.nodes = nodes;
    }

    /**
     * Starts the main user input loop.
     * Reads commands from standard input and executes them until the user exits.
     */
    void userInputLoop() {

        Scanner scanner = new Scanner(System.in);
        boolean exit = false;

        while (!exit) {
            System.out.print("\n> ");
            String line = scanner.nextLine().trim();
            String[] split = line.split(SPACE);
            try {
                switch (split[0]) {
                    case CREATE_BLOCKING:
                        this.create(split, true);
                        break;

                    case CREATE_ASYNC:
                        this.create(split, false);
                        break;

                    case DELETE_BLOCKING:
                        this.delete(split, true);
                        break;

                    case DELETE_ASYNC:
                        this.delete(split, false);
                        break;

                    case BALANCE:
                        this.balance(split);
                        break;

                    case TRANSFER_BLOCKING:
                        this.transfer(split, true);
                        break;

                    case TRANSFER_ASYNC:
                        this.transfer(split, false);
                        break;

                    case DEBUG_BLOCKCHAIN_STATE:
                        this.debugBlockchainState(split);
                        break;

                    case PAUSE:
                        this.pause(split);
                        break;

                    case EXIT:
                        exit = true;
                        break;

                    default:
                        printUsage();
                        break;
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                printUsage();
            }
        }
        scanner.close();
        nodes.forEach(ClientNodeService::shutdown);
    }

    /**
     * Handles the create wallet command.
     *
     * @param split      the command arguments split by spaces
     * @param isBlocking whether the operation should block until completion
     */
    private void create(String[] split, boolean isBlocking) {
        this.checkCreateCommandArgs(split);

        String userId = split[1];
        String walletId = split[2];
        Integer nodeIndex = Integer.parseInt(split[3]);
        Integer nodeDelay = Integer.parseInt(split[4]);

        // Make the call
        ClientNodeService node = nodes.get(nodeIndex);
        Utils.debug("Sending CreateWallet request to node %d: userId=%s, walletId=%s", nodeIndex, userId, walletId);
        if (isBlocking) {
            execute(() -> node.createWallet(userId, walletId, nodeDelay));
        } else {
            node.createWalletAsync(userId, walletId, nodeDelay, createObserver());
        }
    }

    /**
     * Handles the delete wallet command.
     *
     * @param split      the command arguments split by spaces
     * @param isBlocking whether the operation should block until completion
     */
    private void delete(String[] split, boolean isBlocking) {
        this.checkDeleteCommandArgs(split);

        String userId = split[1];
        String walletId = split[2];
        Integer nodeIndex = Integer.parseInt(split[3]);
        Integer nodeDelay = Integer.parseInt(split[4]);

        // Make the call
        ClientNodeService node = nodes.get(nodeIndex);
        Utils.debug("Sending DeleteWallet request to node %d: userId=%s, walletId=%s", nodeIndex, userId, walletId);
        if (isBlocking) {
            execute(() -> node.deleteWallet(userId, walletId, nodeDelay));
        } else {
            node.deleteWalletAsync(userId, walletId, nodeDelay, createObserver());
        }
    }

    /**
     * Handles the read balance command.
     *
     * @param split the command arguments split by spaces
     */
    private void balance(String[] split) {
        this.checkBalanceCommandArgs(split);

        String walletId = split[1];
        Integer nodeIndex = Integer.parseInt(split[2]);
        Integer nodeDelay = Integer.parseInt(split[3]);

        // Make the call
        ClientNodeService node = nodes.get(nodeIndex);
        execute(() -> node.readBalance(walletId, nodeDelay));
    }

    /**
     * Handles the transfer command.
     *
     * @param split      the command arguments split by spaces
     * @param isBlocking whether the operation should block until completion
     */
    private void transfer(String[] split, boolean isBlocking) {
        this.checkTransferCommandArgs(split);

        String userId = split[1];
        String srcWalletId = split[2];
        String dstWalletId = split[3];
        long amount = Long.parseLong(split[4]);
        Integer nodeIndex = Integer.parseInt(split[5]);
        Integer nodeDelay = Integer.parseInt(split[6]);

        // Make the call
        ClientNodeService node = nodes.get(nodeIndex);
        Utils.debug("Sending Transfer request to node %d: userId=%s, srcWalletId=%s, dstWalletId=%s, amount=%d",
                nodeIndex, userId, srcWalletId, dstWalletId, amount);
        if (isBlocking) {
            execute(() -> node.transfer(userId, srcWalletId, dstWalletId, amount, nodeDelay));
        } else {
            node.transferAsync(userId, srcWalletId, dstWalletId, amount, nodeDelay, createObserver());
        }
    }

    /**
     * Handles the debug blockchain state command.
     *
     * @param split the command arguments split by spaces
     */
    private void debugBlockchainState(String[] split) {
        this.checkDebugBlockchainStateArgs(split);

        Integer nodeIndex = Integer.parseInt(split[1]);

        // Make the call
        ClientNodeService node = nodes.get(nodeIndex);
        execute(() -> node.getBlockchainState());
    }

    /**
     * Handles the pause command.
     *
     * @param split the command arguments split by spaces
     */
    private void pause(String[] split) {
        this.checkPauseArgs(split);

        Integer time;

        time = Integer.parseInt(split[1]);

        try {
            Thread.sleep(time * 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Validates the arguments for the create wallet command.
     *
     * @param split the command arguments to validate
     * @throws IllegalArgumentException if the arguments are invalid
     */
    private void checkCreateCommandArgs(String[] split) {
        // C|c <user_id> <wallet_id> <node_index> <node_delay>
        if (split.length != 5) {
            throw new IllegalArgumentException("Expected 5 arguments, got " + split.length);
        }

        if (!ID_PATTERN.matcher(split[1]).matches()) {
            throw new IllegalArgumentException(
                    "Expected User ID to be composed of ASCII alphanumeric characters, got \"" + split[1] + "\"");
        }

        if (!ID_PATTERN.matcher(split[2]).matches()) {
            throw new IllegalArgumentException(
                    "Expected Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[2] + "\"");
        }

        try {
            int nodeIndex = Integer.parseInt(split[3]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
            if (Integer.parseInt(split[4]) < 0) {
                throw new IllegalArgumentException("Node delay cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected initial balance, node number, and node delay to be integers");
        }
    }

    /**
     * Validates the arguments for the delete wallet command.
     *
     * @param split the command arguments to validate
     * @throws IllegalArgumentException if the arguments are invalid
     */
    private void checkDeleteCommandArgs(String[] split) {
        // E|e <user_id> <wallet_id> <node_index> <node_delay>
        if (split.length != 5) {
            throw new IllegalArgumentException("Expected 5 arguments, got " + split.length);
        }

        if (!ID_PATTERN.matcher(split[1]).matches()) {
            throw new IllegalArgumentException(
                    "Expected User ID to be composed of ASCII alphanumeric characters, got \"" + split[1] + "\"");
        }

        if (!ID_PATTERN.matcher(split[2]).matches()) {
            throw new IllegalArgumentException(
                    "Expected Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[2] + "\"");
        }

        try {
            int nodeIndex = Integer.parseInt(split[3]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
            if (Integer.parseInt(split[4]) < 0) {
                throw new IllegalArgumentException("Node delay cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected node number and node delay to be integers");
        }
    }

    /**
     * Validates the arguments for the read balance command.
     *
     * @param split the command arguments to validate
     * @throws IllegalArgumentException if the arguments are invalid
     */
    private void checkBalanceCommandArgs(String[] split) {
        // S|s <wallet_id> <node_index> <node_delay>
        if (split.length != 4) {
            throw new IllegalArgumentException("Expected 4 arguments, got " + split.length);
        }

        if (!ID_PATTERN.matcher(split[1]).matches()) {
            throw new IllegalArgumentException(
                    "Expected Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[1] + "\"");
        }

        try {
            int nodeIndex = Integer.parseInt(split[2]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
            if (Integer.parseInt(split[3]) < 0) {
                throw new IllegalArgumentException("Node delay cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected node number and node delay to be integers");
        }
    }

    /**
     * Validates the arguments for the transfer command.
     *
     * @param split the command arguments to validate
     * @throws IllegalArgumentException if the arguments are invalid
     */
    private void checkTransferCommandArgs(String[] split) {
        // T|t <source_user_id> <source_wallet_id> <destination_wallet_id> <amount>
        // <node_index> <node_delay>
        if (split.length != 7) {
            throw new IllegalArgumentException("Expected 7 arguments, got " + split.length);
        }

        if (!ID_PATTERN.matcher(split[1]).matches()) {
            throw new IllegalArgumentException(
                    "Expected Source User ID to be composed of ASCII alphanumeric characters, got \"" + split[1]
                            + "\"");
        }

        if (!ID_PATTERN.matcher(split[2]).matches()) {
            throw new IllegalArgumentException(
                    "Expected Source Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[2]
                            + "\"");
        }

        if (!ID_PATTERN.matcher(split[3]).matches()) {
            throw new IllegalArgumentException(
                    "Expected Destination Wallet ID to be composed of ASCII alphanumeric characters, got \"" + split[3]
                            + "\"");
        }

        try {
            if (Long.parseLong(split[4]) < 0) {
                throw new IllegalArgumentException("Amount cannot be negative");
            }
            int nodeIndex = Integer.parseInt(split[5]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
            if (Integer.parseInt(split[6]) < 0) {
                throw new IllegalArgumentException("Node delay cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected amount, node number, and node delay to be integers");
        }
    }

    /**
     * Validates the arguments for the debug blockchain state command.
     *
     * @param split the command arguments to validate
     * @throws IllegalArgumentException if the arguments are invalid
     */
    private void checkDebugBlockchainStateArgs(String[] split) {
        // B <node_index>
        if (split.length != 2) {
            throw new IllegalArgumentException("Expected 2 arguments, got " + split.length);
        }

        try {
            int nodeIndex = Integer.parseInt(split[1]);
            if (nodeIndex < 0 || nodeIndex >= this.nodes.size()) {
                throw new IllegalArgumentException("Node index must be between 0 and " + (this.nodes.size() - 1));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected node index to be an integer");
        }
    }

    /**
     * Validates the arguments for the pause command.
     *
     * @param split the command arguments to validate
     * @throws IllegalArgumentException if the arguments are invalid
     */
    private void checkPauseArgs(String[] split) {
        // P <integer>
        if (split.length != 2) {
            throw new IllegalArgumentException("Expected 2 arguments, got " + split.length);
        }

        try {
            if (Integer.parseInt(split[1]) < 0) {
                throw new IllegalArgumentException("Pause time cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected pause time to be an integer");
        }
    }

    private static void printUsage() {
        System.err.println("Usage:\n" +
                "- C|c <user_id> <wallet_id> <node_index> <node_delay>\n" +
                "- E|e <user_id> <wallet_id> <node_index> <node_delay>\n" +
                "- T|t <source_user_id> <source_wallet_id> <destination_wallet_id> <amount> <node_index> <node_delay>\n"
                +
                "- S <wallet_id> <node_index> <node_delay>\n" +
                "- B <node_index>\n" +
                "- P <integer>\n" +
                "- X\n");
    }

    /**
     * Executes a gRPC call and handles the response or errors.
     * Prints the command number and result to standard output.
     *
     * @param grpcCall the gRPC call to execute
     */
    private void execute(Supplier<Message> grpcCall) {
        Long commandNumber = this.commandCounter.incrementAndGet();

        try {
            // Print OK message
            Message response = grpcCall.get();
            System.out.println(commandNumber + " OK");

            // Handle balance
            if (response instanceof ReadBalanceResponse readBalanceResponse) {
                System.out.println("balance: " + readBalanceResponse.getBalance());
                return;
            }

            // Print Response
            String text = response.toString();
            if (!text.isBlank()) {
                System.out.println(text);
            }

        } catch (StatusRuntimeException e) {
            // Handle error
            System.err.println("[Error] " + commandNumber + ": " + e.getStatus().getDescription());
        }
    }

    /**
     * Generates a new command number and creates a generic StreamObserver
     * to handle the async gRPC response.
     *
     * @param <T> the type of the gRPC response message
     * @return a properly configured MessageObserver
     */
    private <T> StreamObserver<T> createObserver() {
        Long commandNumber = this.commandCounter.incrementAndGet();
        return new MessageObserver<>(commandNumber);
    }
}
