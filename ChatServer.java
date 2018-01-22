

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
/*
 * class ChatServer
 * 
 * Used to forward data from the browser.
 * 
 * Variable explanation:
 * CMDS(String[]) : 	Store instruction set.
 * COPS(String[]) : 	Saves a regular expression that determines 
 * 						whether a command is valid or not.
 * connections(Set) : 	Static.Save all connected ChatServer objects.
 * groups(Map) : 		Static.Save all group ids with their group
 * 						name and number of members.
 * joinedGroups(Set) : 	Save all the groups that this user has joined.
 * 
 */
@ServerEndpoint(value = "/server")
public class ChatServer {
	static PrintStream debug = System.out;
	
    private static final Set<ChatServer> connections =
            new CopyOnWriteArraySet<ChatServer>();
    private static final Map<Integer,Object[]> groups = 
    		new TreeMap<Integer,Object[]>();
    private static final int MAX_G = 100;
    private static final int MAX_U = 1000;
    private static Boolean[] UID_SET = null,GID_SET = null;
    private static final String[] CMDS = {
    		"USER","NEWG","JOIN","LINK","EXIT","TEXT"
    };
    private static final String[] COPS = {
    		"USER [^ ]+",
    		"NEWG [^ ]+",
    		"JOIN [0-9]+",
    		"LINK [0-9]+ [^ ]+",
    		"EXIT [0-9]+",
    		"TEXT [0-9]+ [^ ]+"
    };
    private static final boolean isCmd(String str){		//Judge whether a command is a valid regular expression.
	    for(int i=0;i<CMDS.length;i++){
	    	Pattern pattern = Pattern.compile(COPS[i]);
	    	Matcher matcher = pattern.matcher(str);
	    	if(matcher.matches())return true;
	    }
	    return false;
    }
    private int newUID(){								//Get a new user ID.
    	if(UID_SET == null){
    		UID_SET = new Boolean[MAX_U];
    		for(int i=0;i<MAX_U;i++)UID_SET[i] = false;
    	}
    	for(int i=0;i<MAX_U;i++)if(!UID_SET[i]){
    		UID_SET[i] = true;
    		return i;
    	}
    	return -1;
    }
    private int newGID(){								//Get a new group ID.
    	if(GID_SET == null){
    		GID_SET = new Boolean[MAX_G];
    		for(int i=0;i<MAX_G;i++)GID_SET[i] = false;
    	}
    	for(int i=0;i<MAX_G;i++)if(!GID_SET[i]){
    		GID_SET[i] = true;
    		return i;
    	}
    	return -1;
    }
    
    private String nickname;
    private Session session;
    private int ID = -1;
    private Set<Integer> joinedGroups = new TreeSet<Integer>();

    @OnOpen
    public void OnOpen(Session session) {
    	debug.println("Open");
    	this.session = session;  
        connections.add(this); 
    }

    @OnClose
    public void OnClose() {
    	debug.println("Close");
    	connections.remove(this);
    	doBeforeClose();
    	sendList();
    }

    @OnMessage
    public void OnMessage(String message) {				//Category discussion and processing orders.
    	debug.println("Message : "+message);
    	if(!isCmd(message)){
    		debug.println("ban : "+message);
    		return;
    	}
    	if(message.startsWith("USER")){
			ID = newUID();
			if(ID == -1)send("UFULL");
			nickname = message.substring(5);
			sendList();
		}else if(message.startsWith("NEWG")){
			int gid = newGID();
			if(gid == -1)send("GFULL");
			String gname = message.substring(5);
			Object[] os = new Object[2];
			os[0] = gname; os[1] = 1;
			groups.put(gid,os);
			joinedGroups.add(gid);
			send("PUSH "+gid+" "+gname);
			sendList();
		}else if(message.startsWith("JOIN")){
			int gid = Integer.valueOf(message.substring(5));
			if(groups.containsKey(gid) && !joinedGroups.contains(gid)){
				joinedGroups.add(gid);
				Object[] os = groups.get(gid);
				send("PUSH "+gid+" "+os[0]);
				os[1] = (Integer)os[1]+1;
				broadcast("TEXT "+gid+" <messagesys>"+nickname+"_加入了会话</messagesys>");
			}
			sendList();
		}else if(message.startsWith("LINK")){
			String[] cmds = message.split(" ");
			int pid = Integer.valueOf(cmds[1]);
			if(pid == ID)return;
			ChatServer ps = null;
			for(ChatServer client : connections){
	    		if(client.ID == pid){
	    			ps = client;
	    			break;
	    		}
	    	}
			if(ps == null)return;
			int gid = newGID();
			ps.joinedGroups.add(gid);
			joinedGroups.add(gid);
			ps.send("PUSH "+gid+" <linkwith_id=\"lw"+ID+"\"></linkwith>与_"+nickname+"_聊天中");
			send("PUSH "+gid+" <linkwith_id=\"lw"+pid+"\"></linkwith>与_"+cmds[2]+"_聊天中");
			sendList();
		}else if(message.startsWith("EXIT")){
			String[] ops = message.split(" ");
			int gid = Integer.valueOf(ops[1]);
			joinedGroups.remove(gid);
			String linkLeaveMessage = "";
			if(groups.containsKey(gid)){
	    		Object[] os = groups.get(gid);
	    		os[1] = (Integer)os[1]-1;
	    		if((Integer)os[1] == 0)groups.remove(gid);
    		}else linkLeaveMessage = "<leave_id=\"llv"+gid+"\"></leave>";
			sendList();
			broadcast("TEXT "+gid+" "+linkLeaveMessage+"<messagesys>"+nickname+"_已经离开了会话</messagesys>");
		}else if(message.startsWith("TEXT")){
			broadcast(message);
		}
    }

    @OnError
    public void onError(Throwable t) throws Throwable {
    	t.printStackTrace();
    }
    
    private static void sendList(){						//Send the JSON string of the user list and group list.
    	broadcast("_");
    	for (ChatServer client : connections) {
            try {
                synchronized (client) {
                    client.session.getBasicRemote().sendText(
                    		"LIST "+
                    		"{\"yourID\":"+client.ID+
                    		",\"grouplist\":"+getGroupStr()+
                    		",\"userlist\":"+getUserStr()+"}"
                    );
                }
            } catch (IOException e) {
                client.doBeforeClose();
                connections.remove(client);
                try {
                    client.session.close();
                } catch (IOException e1) {
                    // Ignore
                }
            }
        }
    }
    
    static private String getGroupStr(){				//Get the JSON string of the group list.
    	int con = 0;
    	Object[][] os = new Object[MAX_G][3];
    	for(Integer i : groups.keySet())os[con++][0] = i;
    	con = 0;
    	for(Object[] p : groups.values()){
    		os[con][1] = p[0]; os[con][2] = p[1];
    		con++;
    	}
    	String res = "[";
    	for(int i=0;i<con;i++){
			res += "{";
    		res += "\"groupname\":\""+os[i][1]+"\","+
    			   "\"numberofpeople\":"+os[i][2]+","+
    			   "\"groupID\":"+os[i][0];
    		res += "}"+(i==con-1?"":",");
    	}
    	res += "]";
    	return res;
    }
    static private String getUserStr(){					//Get the JSON string of the user list.
    	String res = "["; boolean has = false;
    	for(ChatServer client : connections){
    		//userID:10,nickname:"张三"
    		if(has)res += ",";else has = true;
    		res += "{";
    		res += "\"userID\":"+client.ID+
    				",\"nickname\":\""+client.nickname+"\"";
    		res += "}";
    	}
    	res += "]";
    	return res;
    }

    
    public void doBeforeClose(){
    	UID_SET[ID] = false; ID = -1;
    	debug.println("joined : "+joinedGroups.size());
    	for(int gid : joinedGroups){
    		String linkLeaveMessage = "";
    		if(groups.containsKey(gid)){
	    		Object[] os = groups.get(gid);
	    		os[1] = (Integer)os[1]-1;
	    		if((Integer)os[1] == 0)groups.remove(gid);
    		}else linkLeaveMessage = "<leave_id=\"llv+"+gid+"\"></leave>";
    		broadcast("TEXT "+gid+" "+linkLeaveMessage+"<messagesys>"+nickname+"_已经离开了会话</messagesys>");
    	}
    }
    
    public boolean send(String msg){				//Send message to this user.
    	try {
    		synchronized (this) {
    			this.session.getBasicRemote().sendText(msg);
    		}
    		return true;
		} catch (IOException e) {
			doBeforeClose();
			connections.remove(this);
			try {
                session.close();
            } catch (IOException e1) {
                // Ignore
            }
		}
    	return false;
    }
    
    private static void broadcast(String msg) {		//Send message to all users.
    	debug.println("broadcast : "+msg);
        for (ChatServer client : connections) {
            try {
                synchronized (client) {
                	if(client.session.isOpen())
                		client.session.getBasicRemote().sendText(msg);
                	else throw new IOException();
                }
            } catch (IOException e) {
            	debug.println("err1 : "+e.getMessage());
                client.doBeforeClose();
                connections.remove(client);
                try {
                    client.session.close();
                } catch (IOException e1) {
                	debug.println("err2 : "+e1.getMessage());
                }
            }
        }
    }
}
