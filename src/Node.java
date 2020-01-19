package src;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class Node {
    private int id;
    private String ip;
    private int port;

    private List<Address> clusterNodes;
    private List<Address> interNodes;
    private int totalLength;
    private int cluster;
    private StorageSystem nodeData;

    private ServerSocket serverSocket;
    private Thread listeningThread;
    private StorageSystem serverData;

    public Node(String startIp, int startPort)
            throws FileNotFoundException, ClassNotFoundException, IOException, InterruptedException {
        // init
        this.clusterNodes = new ArrayList<>();
        this.interNodes = new ArrayList<>();
        //
        this.nodeData = new StorageSystem("", "nodeData.node");
        this.serverData = new StorageSystem("", "serverData.data");
        // get ip
        this.ip = getCurrentIp();
        // load node data
        if (this.nodeData.get("id") == null) {
            this.id = generateId();
            if(startIp != null) joinCluster(startIp, startPort);
            else{
                this.totalLength = 1;
                this.cluster = 0;
            }
            saveNode();
        } 
        else  loadNode();
        // refresh id every 5 minutes
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    refreshIp();
                } catch (IOException | InterruptedException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }, 0, 1000 * 60 * 5);
        // start server
        startServer(8000);
        // check ip every minute

    }

    private void joinCluster(String startIp, int startPort) throws UnknownHostException, IOException, ClassNotFoundException {
        System.out.println("join");
        Client client = new Client();
        Datapackage answer = client.sendDatapackage(startIp, startPort, new Datapackage(0, null, getAddress(), null, 0), true);
        System.out.println(answer.getId());
        if(answer.getId() == -1)    answer = client.sendDatapackage(startIp, startPort, new Datapackage(0, null, getAddress(), null, answer.getCluster()), true);
        List<List<Address>> list = (List<List<Address>>) answer.getPayload();
        System.out.println(list.get(0));


        this.clusterNodes = list.get(0);

        List<Address> tempInterNodes = list.get(1);

        this.clusterNodes.add(new Address(answer.getId(), startIp, startPort, answer.getCluster()));

        this.cluster = answer.getCluster();
        //
        for(Address add : tempInterNodes){
            answer = client.sendDatapackage(add.getIp(), add.getPort(), new Datapackage(3, String.valueOf(clusterNodes.size()), getAddress(), null, this.cluster), true);
            if(answer != null)  this.interNodes.add((Address) answer.getPayload());
        }
    }

    private void refreshIp() throws IOException, InterruptedException, ClassNotFoundException {
        String tempIp = getCurrentIp();
        if(!this.ip.equals(tempIp)){
            this.ip = tempIp;
            saveNode();
            for(Address add : this.clusterNodes) new Client().sendDatapackage(new Socket(add.getIp(), add.getPort()), new Datapackage(1, null, getAddress(), null, add.cluster), false);
            for(Address add : this.interNodes)   new Client().sendDatapackage(new Socket(add.getIp(), add.getPort()), new Datapackage(1, null, getAddress(), null, add.cluster), false);
        }
    }

    private void loadNode(){
        this.id = (int) this.nodeData.get("id");
        this.clusterNodes = (List<Address>) this.nodeData.get("clusterNodes");
        this.interNodes = (List<Address>) this.nodeData.get("interNodes");
        Collections.sort(this.clusterNodes, new SortAddress());
    }

    private void saveNode() throws FileNotFoundException, IOException {
        this.nodeData.put("id", this.id);
        this.nodeData.put("clusterNodes", this.clusterNodes);
        this.nodeData.put("interNodes", this.interNodes);
        this.nodeData.store();
    }

    private int generateId(){
        return String.valueOf(Math.random()).hashCode();
    }

    private void startServer(int port) throws FileNotFoundException, ClassNotFoundException, IOException {
        this.port = port;
        this.serverData = new StorageSystem("", "serverData.data");
        this.serverSocket = new ServerSocket(this.port);

        if (this.listeningThread == null && serverSocket != null) {
            this.listeningThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.interrupted() && serverSocket != null) {

                        try {
                            // waiting for client
                            final Socket tempSocket = serverSocket.accept();

                            // retrieving sent data
                            ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(tempSocket.getInputStream()));
                            Object recievedObject = ois.readObject();

                            if (recievedObject instanceof Datapackage) {
                                Datapackage recievedDatapackage = (Datapackage) recievedObject;
                                System.out.println(recievedDatapackage.getId());
                                if(!passOn(recievedDatapackage, tempSocket)){
                                    if(recievedDatapackage.getId() == 0)    integrateToCluster(recievedDatapackage, tempSocket);
                                    else if(recievedDatapackage.getId() == 1)    changeIp(recievedDatapackage);
                                    else if(recievedDatapackage.getId() == 2)    addAddress(recievedDatapackage);
                                    else if(recievedDatapackage.getId() == 3)    coupleNodes(recievedDatapackage, tempSocket);
                                    else if(recievedDatapackage.getId() == 4)    storeData(recievedDatapackage);
                                    else if(recievedDatapackage.getId() == 5 )  spreadData(recievedDatapackage);
                                    else if(recievedDatapackage.getId() == 6)   pushData(recievedDatapackage, tempSocket);
                                    else if(recievedDatapackage.getId() == 7)   spreadDeleteData(recievedDatapackage);
                                    else if(recievedDatapackage.getId() == 8)   addStorage(recievedDatapackage);
                                    else if(recievedDatapackage.getId() == 9)   spreadAdd(recievedDatapackage);
                                    else if(recievedDatapackage.getId() == 10)   deleteServerData(recievedDatapackage);
                                }                           
                             }
                             ois.close();
                        } catch (SocketException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (ClassNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            listeningThread.start();
        }
    }

    private void addStorage(Datapackage datapackage) throws FileNotFoundException, IOException {
        if(this.serverData.get(datapackage.getName()) == null) {
            this.serverData.put(datapackage.getName(), datapackage.getPayload());
        }
        else if(((Datapackage) this.serverData.get(datapackage.getName())).getOwner().equals(datapackage.getOwner())) {
            if(datapackage.getPayload() instanceof List){
                ((List<byte[]>) ((Datapackage) this.serverData.get(datapackage.getName())).getPayload()).addAll((List<byte[]>) datapackage.getPayload());
            }
        }
        this.serverData.store();
    }

    private void spreadAdd(Datapackage datapackage) throws ClassNotFoundException, UnknownHostException, IOException {
        spread(new Datapackage(8, datapackage.getName(), datapackage.getPayload(), datapackage.getOwner(), this.cluster));
        addStorage(datapackage);
    }

    private void spread(Datapackage datapackage) throws ClassNotFoundException, UnknownHostException, IOException {
        Client client = new Client();
        for(Address add : this.clusterNodes){
            System.out.println(add.getId());
            client.sendDatapackage(add.getIp(), 8000, datapackage, false);
            System.out.println("yay");
        }
    }

    private void deleteServerData(Datapackage datapackage){
        if(((Datapackage) this.serverData.get(datapackage.getName())).getOwner().equals(datapackage.getOwner()))    this.serverData.remove(datapackage.getName());
    }

    private void spreadDeleteData(Datapackage datapackage)throws ClassNotFoundException, UnknownHostException, IOException {
        spread(new Datapackage(9, datapackage.getName(), null, datapackage.getOwner(), this.cluster));
        deleteServerData(datapackage);
    }

    private void pushData(Datapackage datapackage, Socket socket) throws ClassNotFoundException, IOException {
        System.out.println("push: " + (this.serverData.get(datapackage.getName()) == null));
        new Client().sendDatapackage(socket, new Datapackage(-1, datapackage.getName(), this.serverData.get(datapackage.getName()), null, this.cluster), false);
    }

    private void spreadData(Datapackage datapackage) throws ClassNotFoundException, UnknownHostException, IOException {
        spread(new Datapackage(4, null, datapackage.getPayload(), datapackage.getOwner(), this.cluster));
        System.out.println("ok");
        storeData(datapackage);
        System.out.println("finished");
    }

    private void storeData(Datapackage datapackage) throws FileNotFoundException, IOException {
        this.serverData.put(datapackage.getName(), datapackage);
        this.serverData.store();
    }

    private void coupleNodes(Datapackage datapackage, Socket socket) throws ClassNotFoundException, IOException {
        Client client = new Client();
        Collections.sort(this.clusterNodes, new SortAddress());
        Address address = this.clusterNodes.get(Integer.valueOf(datapackage.getName()) - 1);
        client.sendDatapackage(socket, new Datapackage(-1, null, address, null, this.cluster), false);
        client.sendDatapackage(address.getIp(), address.getPort(), new Datapackage(2, null, datapackage.getPayload(), null, ((Address) datapackage.getPayload()).getCluster()), false);
    }

    private void addAddress(Datapackage datapackage){
        Address address = (Address) datapackage.getPayload();
        if(address.getCluster() == this.cluster){
            this.clusterNodes.add(address);
            return;
        }
        this.interNodes.add(address);
    }

    private void changeIp(Datapackage datapackage){
        Address changingAddress = (Address) datapackage.getPayload();
        if(changingAddress.getCluster() == this.cluster){
            for(Address add : this.clusterNodes){
                if(add.getId() == changingAddress.getId()){
                    add.setId(changingAddress.getId());
                    return;
                }
            }
            return;
        }
        for(Address add : this.interNodes){
            if(add.getId() == changingAddress.getId()){
                add.setId(changingAddress.getId());
            }
        }
    }

    private void integrateToCluster(Datapackage datapackage, Socket socket) throws ClassNotFoundException, UnknownHostException, IOException {
        Client client = new Client();
        for(Address add : this.clusterNodes)    client.sendDatapackage(add.getIp(), add.getPort(), new Datapackage(2, null, datapackage.getPayload(), null, this.cluster), false);
        List<List<Address>> list = (List<List<Address>>) new ArrayList();
        list.add(this.clusterNodes);
        list.add(this.interNodes);
        client.sendDatapackage(socket, new Datapackage(this.id, null, list, null, this.cluster), false);
        this.clusterNodes.add((Address) datapackage.getPayload());
        saveNode();
    }

    private boolean passOn(Datapackage datapackage, Socket socket) throws ClassNotFoundException, UnknownHostException, IOException {
        if(datapackage.getId() == 0){
            //let node join this cluster
            if(this.clusterNodes.size() < 9)    return false;
            //pass node on to the last cluster
            if(this.cluster < this.totalLength-1){
                new Client().sendDatapackage(socket, new Datapackage(-1, null, null, null, null, this.totalLength), false);
                return false;
            }
            //let node create a new cluster
            Collections.sort(this.clusterNodes, new SortAddress());
            Address recipient = this.clusterNodes.get(0);
            new Client().sendDatapackage(recipient.getIp(), recipient.getPort(), new Datapackage(2, null, datapackage.getPayload(), null, this.totalLength+1), false);
            return false;
        }
        if(datapackage.getCluster() == this.cluster)  return false;
        if(datapackage.getCluster() > this.totalLength){
            this.totalLength += 1;
            return false;
        }
        new Client().sendSoftDatapackage(datapackage);
        return true;
    }

    private Address getAddress(){
        return new Address(this.id, this.ip, this.port, this.cluster);
    }

    /**
     * calls a Ruby-script to get own ipv6 address
     * @return the current ip as a String
     * @throws IOException
     * @throws InterruptedException
     */
    private String getCurrentIp() throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec("ruby src/ip.rb");
        process.waitFor();
        BufferedReader processIn = new BufferedReader(new InputStreamReader(process.getInputStream()));
        return processIn.readLine();
    }


    public static void main(String[] args)throws FileNotFoundException, ClassNotFoundException, IOException, InterruptedException {
        Node node = new Node(null, 8000);
        System.out.println(node.getCurrentIp());
        System.out.println(node.id);
        System.out.println(node.clusterNodes.get(0).getId());
    }
}