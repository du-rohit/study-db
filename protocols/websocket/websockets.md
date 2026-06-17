# WebSockets — Study Notes

## Stage 1: Concepts

### Why WebSockets Exist

HTTP is stateless and client-initiated — the server can never send data unprompted. This breaks down for real-time scenarios.

#### Workarounds Before WebSockets

**Polling** — client asks server every N seconds
- Simple but wasteful; makes hundreds of useless requests
- Average latency = half the poll interval

**Long Polling** — client asks, server holds connection open until data is ready
- Better latency, but server holds thousands of open connections
- High resource cost, complex to manage

**Server-Sent Events (SSE)** — one-way stream from server to client over HTTP
- Works for dashboards/feeds, but client can't send data back on the same connection

#### WebSocket: The Real Fix

Establishes a **persistent, full-duplex TCP connection**. Both sides can send data at any time.

**The Handshake:**
1. Client sends a normal HTTP request with `Upgrade: websocket` header
2. Server responds with HTTP `101 Switching Protocols`
3. TCP connection is "promoted" — HTTP is done, WebSocket takes over
4. Connection stays open until either side closes it

```
Client ──── HTTP Upgrade Request ────► Server
Client ◄─── 101 Switching Protocols ── Server

Client ──── "message" ───────────────► Server
Client ◄─── "pushed update" ────────── Server  ← server initiates, no request needed
```

#### HTTP vs WebSocket

| Property | HTTP | WebSocket |
|---|---|---|
| Who initiates | Client always | Either side |
| Connection | Opens/closes per request | Persistent |
| Overhead | Headers on every request | Only on handshake |
| Direction | Request → Response | Full-duplex |

#### Real Use Cases
- Chat applications
- Live notifications
- Stock tickers / real-time dashboards
- Multiplayer games
- Collaborative editing (Google Docs-style)

#### Scaling Gotcha
WebSockets are **stateful** — the server holds open connections for every active client.
- Horizontal scaling is harder: a message for client A must reach the server instance holding A's connection
- Production fix: use a **message broker** (Redis Pub/Sub, Kafka) between server instances
- This is what Spring Boot + STOMP (Stage 4) solves

---

## Stage 2: Echo Server (Raw Java WebSocket API)

### Stack
- **Tyrus** — reference implementation of the Jakarta WebSocket spec (JSR-356). Runs an embedded Grizzly HTTP server that handles the upgrade handshake.
- **`java.net.http.WebSocket`** — built-in Java 11+ client. No extra dependency needed.

### Project structure
```
websocket-echo/
├── pom.xml
└── src/main/java/com/study/websocket/
    ├── EchoEndpoint.java     ← server-side WebSocket endpoint
    ├── ServerLauncher.java   ← starts the embedded server
    └── EchoClient.java       ← test client
```

### The 4 Lifecycle Annotations (EchoEndpoint.java)

```java
@ServerEndpoint("/echo")          // maps this class to ws://host/ws/echo
public class EchoEndpoint {

    @OnOpen                        // fires when a client connects
    public void onOpen(Session session) { ... }

    @OnMessage                     // fires when a message arrives
    public String onMessage(String message, Session session) {
        return "Echo: " + message; // return value is auto-sent back to sender
    }

    @OnClose                       // fires when a client disconnects (clean or not)
    public void onClose(Session session, CloseReason reason) { ... }

    @OnError                       // fires on unhandled errors
    public void onError(Session session, Throwable error) { ... }
}
```

`Session` represents one connected client. It has a unique `session.getId()` — this is how you distinguish between clients when many are connected.

### The HTTP Upgrade Handshake

WebSocket always starts as an HTTP request. The client sends:
```
GET /ws/echo HTTP/1.1
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: <random base64>
```
Server replies:
```
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Sec-WebSocket-Accept: <hashed key>
```
After `101`, the TCP connection is "promoted" — HTTP is done, raw WebSocket frames flow over it.

### Two Key Client Concepts

**`ws.request(1)` — flow control**
The Java WebSocket client won't deliver incoming messages to your listener until you ask for them. `request(1)` means "I'm ready for 1 more message." Call it again in `onText` to keep receiving. Without it, `onText` never fires.

**`CountDownLatch` — thread synchronisation**
WebSocket callbacks fire on background threads. `CountDownLatch(1)` lets the main thread park itself (`await()`) until a callback calls `countDown()` to release it. Without this, `main()` would exit before any message arrived.

### Full Message Flow

```
Client                                    Server
  |--- HTTP GET /ws/echo (Upgrade) --------->|
  |<-- 101 Switching Protocols --------------|
  |         [TCP connection promoted]        |
  |     onOpen fires on both sides           |
  |--- TEXT frame "Hello WebSocket!" ------->|
  |                   onMessage fires → returns echo
  |<-- TEXT frame "Echo: Hello WebSocket!" --|
  |     onText fires on client               |
  |--- CLOSE frame (1000, "done") ---------->|
  |                   onClose fires on server
  |<-- CLOSE frame --------------------------|
  |     onClose fires on client              |
  |         [TCP connection closed]          |
```

### Run it
```bash
# Terminal 1 — server
mvn exec:java -Dexec.mainClass=com.study.websocket.ServerLauncher -q

# Terminal 2 — client
mvn exec:java -Dexec.mainClass=com.study.websocket.EchoClient -q
```

---

## Stage 3: Real-time Chat Room

### New files
- `ChatEndpoint.java` — broadcasts to all connected clients
- `ChatClient.java` — interactive client that reads from stdin

### One instance per connection

Tyrus creates a **new `ChatEndpoint` object for every client that connects**. Three clients = three separate instances. This is the fundamental design constraint everything else flows from.

```
Alice connects → new ChatEndpoint()   [instance A]
Bob connects   → new ChatEndpoint()   [instance B]
Carol connects → new ChatEndpoint()   [instance C]
```

A regular instance field would only be visible within one instance. The sessions set must be `static` so it lives on the class and is shared across all instances:

```java
// static = one set for the entire class, shared by all instances
private static final Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());
```

### Thread safety

**`Collections.synchronizedSet`** — Multiple clients can connect/disconnect simultaneously on different threads. A plain `HashSet` is not thread-safe. `synchronizedSet` locks `add`, `remove`, and `size` so only one thread modifies the set at a time.

**`Set.copyOf(sessions)` in broadcast** — Even `synchronizedSet` is unsafe to iterate while another thread modifies it (throws `ConcurrentModificationException`). `Set.copyOf` takes an immutable snapshot; the loop iterates the snapshot safely while the live set can change freely underneath.

### `getAsyncRemote()` vs `getBasicRemote()`

```java
s.getAsyncRemote().sendText(message); // non-blocking
```

| | `getBasicRemote()` | `getAsyncRemote()` |
|---|---|---|
| Blocks until sent | Yes | No |
| Safe from multiple threads | No | Yes |
| Risk | Stalls loop if one client is slow | None |

In a broadcast loop, one slow client would stall all others with `getBasicRemote()`. `getAsyncRemote()` hands the message to a send queue and moves on immediately.

### `@OnMessage` returns void

When the return value is a `String`, Tyrus auto-sends it back to the **sender only**. For broadcast, return `void` and write to sessions manually — you control exactly who gets the message.

### `s.isOpen()` guard

There's a race window between taking the snapshot and sending. A session can close between those two moments. `isOpen()` is a cheap guard that prevents sending to a closed session.

### Broadcast flow

```
Alice sends "hello"
    ↓
onMessage fires on Alice's ChatEndpoint instance
    ↓
broadcast() iterates Set.copyOf(sessions) → {Alice, Bob, Carol}
    ↓
getAsyncRemote().sendText() called for each session
    ↓
All three clients receive: "Alice: hello"
```

### The horizontal scaling problem

The `static` set is in-process memory — it doesn't cross JVM boundaries. With two server instances:

```
              ┌─────────────┐
Alice ───────►│  Server 1   │  sessions = {Alice, Bob}
Bob   ───────►│             │
              └─────────────┘
              ┌─────────────┐
Carol ───────►│  Server 2   │  sessions = {Carol}
              └─────────────┘
```

Alice's message only reaches Alice and Bob. Carol never sees it.

**Fix:** a message broker (Redis Pub/Sub, Kafka) sits between server instances. Server 1 publishes to the broker; both servers subscribe and broadcast to their local sessions. This is what Stage 4 solves.

### Run it
```bash
# Terminal 1 — server
mvn exec:java -Dexec.mainClass=com.study.websocket.ServerLauncher -q

# Terminals 2, 3, 4 — clients with names as args
mvn exec:java -Dexec.mainClass=com.study.websocket.ChatClient -Dexec.args="Alice" -q
mvn exec:java -Dexec.mainClass=com.study.websocket.ChatClient -Dexec.args="Bob" -q
mvn exec:java -Dexec.mainClass=com.study.websocket.ChatClient -Dexec.args="Carol" -q
```
Type `/quit` to disconnect a client.

---

## Stage 4: Spring Boot + STOMP (Production Pattern)

### What STOMP adds on top of WebSocket

STOMP (Simple Text Oriented Messaging Protocol) is a subprotocol that runs over WebSocket. It adds structure that raw WebSocket lacks:
- **Destinations** — clients send to `/app/chat.send` and subscribe to `/topic/chat` instead of a raw URL
- **Message broker** — routes messages between publishers and subscribers automatically
- **JSON serialisation** — Spring handles it; you work with Java objects

### Project structure
```
websocket-chat-spring/
├── pom.xml
└── src/main/java/com/study/chat/
    ├── ChatApplication.java          ← @SpringBootApplication entry point
    ├── WebSocketConfig.java          ← configures STOMP routing and endpoints
    ├── ChatMessage.java              ← DTO (type, sender, content)
    ├── ChatController.java           ← @MessageMapping handlers
    └── WebSocketEventListener.java   ← handles disconnect events
└── src/main/resources/static/
    └── index.html                    ← browser test client (SockJS + STOMP.js)
```

### WebSocketConfig.java — the routing table

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");           // in-memory broker for broadcasts
        registry.setApplicationDestinationPrefixes("/app"); // routes to @MessageMapping methods
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-chat").withSockJS();   // connection URL + SockJS fallback
    }
}
```

Two routing lanes:
- `/app/...` → your controller `@MessageMapping` methods (business logic runs)
- `/topic/...` → in-memory broker (broadcasts directly to all subscribers, no code runs)

`withSockJS()` adds automatic fallback — if a browser doesn't support WebSocket, SockJS silently falls back to long-polling. Same real-time behaviour either way.

### ChatController.java — message handlers

```java
@MessageMapping("/chat.send")     // handles messages sent to /app/chat.send
@SendTo("/topic/chat")            // return value is broadcast to all /topic/chat subscribers
public ChatMessage sendMessage(@Payload ChatMessage message) {
    return message;               // Spring auto-deserialises input and serialises output
}

@MessageMapping("/chat.join")
@SendTo("/topic/chat")
public ChatMessage join(@Payload ChatMessage message, SimpMessageHeaderAccessor headerAccessor) {
    // Store username in session so WebSocketEventListener can retrieve it on disconnect
    headerAccessor.getSessionAttributes().put("username", message.getSender());
    message.setType(ChatMessage.Type.JOIN);
    return message;
}
```

### WebSocketEventListener.java — the disconnect problem

In Stage 3, `@OnClose` gave you the session directly. Spring's disconnect event is different — the session attributes are buried inside the message headers and must be unwrapped:

```java
@EventListener
public void handleDisconnect(SessionDisconnectEvent event) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
    String username = (String) accessor.getSessionAttributes().get("username");

    // SimpMessagingTemplate = programmatic send, usable anywhere (not just controllers)
    messagingTemplate.convertAndSend("/topic/chat", leaveMessage);
}
```

### Stage 3 vs Stage 4 comparison

| Stage 3 (raw) | Stage 4 (Spring + STOMP) |
|---|---|
| `static Set<Session>` managed manually | In-memory broker manages subscribers |
| Manual `synchronizedSet` + `Set.copyOf` | Spring handles thread safety |
| `getAsyncRemote().sendText(...)` loop | `@SendTo` or `messagingTemplate.convertAndSend()` |
| Manual JSON parsing | `@Payload` auto-deserialises |
| `@OnClose` gives you the session | `SessionDisconnectEvent` + header unwrapping |
| Single JVM only | Swap broker config to scale across JVMs |

### Scaling to multiple servers

Replace the in-memory broker with a real external broker — just a config change, no controller code changes:

```java
// In-memory (single server only)
registry.enableSimpleBroker("/topic");

// External broker (scales across servers — server instances share the broker)
registry.enableStompBrokerRelay("/topic")
    .setRelayHost("localhost")
    .setRelayPort(61613); // RabbitMQ or ActiveMQ
```

Each server instance subscribes to the external broker. A message published by Server 1 is received by Server 2 and broadcast to its local sessions. Carol gets the message even though she's on a different server than Alice.

### How it all flows — step by step

#### The protocol stack

```
TCP
 └── WebSocket  (persistent full-duplex connection, raw frames)
      └── SockJS  (adds fallback transport + heartbeat)
           └── STOMP  (adds destinations, subscriptions, structured routing)
```

Raw WebSocket (Stages 2 & 3) was just TCP + WebSocket. STOMP sits on top and gives messages *meaning* — a destination, a type, headers. Without STOMP you invent your own routing; STOMP is that routing convention.

#### Step 1: Alice clicks Join — connection handshake

```javascript
var socket = new SockJS('/ws-chat');
stompClient = Stomp.over(socket);
stompClient.connect({}, function() { ... });
```

1. SockJS makes a plain HTTP GET to `/ws-chat/info` — asks the server "do you support WebSocket?"
2. Server says yes → standard HTTP `101 Switching Protocols` upgrade happens (same as Stage 2)
3. STOMP sends its own handshake over that WebSocket connection:

```
→ CONNECT
  accept-version:1.1,1.2
  heart-beat:4000,4000

← CONNECTED
  version:1.2
  heart-beat:0,0
```

Only now does `stompClient.connect()` fire your callback. `heart-beat` sets up periodic ping/pong to detect dead connections — raw WebSocket doesn't do this for you.

#### Step 2: Alice subscribes to the topic

```javascript
stompClient.subscribe('/topic/chat', function(payload) { ... });
```

Sends a STOMP SUBSCRIBE frame:
```
→ SUBSCRIBE
  id:sub-0
  destination:/topic/chat
```

The in-memory broker registers: *"this session wants messages from /topic/chat."* Nothing else happens — Alice is just listening. Bob and Carol do the same. The broker now holds three subscribers.

#### Step 3: Alice announces she joined

```javascript
stompClient.send('/app/chat.join', {}, JSON.stringify({ sender: 'Alice', content: '' }));
```

Sends a STOMP SEND frame:
```
→ SEND
  destination:/app/chat.join
  content-type:application/json

  {"sender":"Alice","content":""}
```

Destination starts with `/app` → Spring routes to `@MessageMapping("/chat.join")`. Spring deserialises the JSON body into a `ChatMessage` object automatically. The method stores `"Alice"` in session attributes (needed for disconnect later), sets type to JOIN, returns the object. `@SendTo("/topic/chat")` serialises it back to JSON and hands it to the broker.

Broker delivers to all three subscribers:
```
← MESSAGE
  destination:/topic/chat
  content-type:application/json

  {"type":"JOIN","sender":"Alice","content":null}
```

All three browsers render: `▶ Alice joined`.

#### Step 4: Alice sends "hello"

Same routing path — destination `/app/chat.send` → `@MessageMapping("/chat.send")` → return value broadcast via `@SendTo("/topic/chat")`. The controller is just a function: input in, output out. No session management, no thread safety concerns — the broker handles all of that.

#### Step 5: Alice clicks Leave — disconnect

```javascript
stompClient.disconnect(); // sends STOMP DISCONNECT frame, closes WebSocket
```

Spring detects the closed session and fires `SessionDisconnectEvent`. `WebSocketEventListener` handles it:

```java
StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
String username = (String) accessor.getSessionAttributes().get("username"); // → "Alice"
messagingTemplate.convertAndSend("/topic/chat", leaveMessage);
```

Username was stored in Step 3. `convertAndSend` is the programmatic equivalent of `@SendTo` — usable anywhere in the application, not just controllers. Bob and Carol see: `◀ Alice left`.

#### Full sequence diagram

```
Alice's browser                Spring server               Bob & Carol's browsers
      |                              |                              |
      |--SockJS HTTP info check----->|                              |
      |<-WebSocket upgrade-----------|                              |
      |--STOMP CONNECT-------------->|                              |
      |<-STOMP CONNECTED-------------|                              |
      |--SUBSCRIBE /topic/chat------>| broker registers Alice       |
      |                              | broker registers Bob & Carol |
      |--SEND /app/chat.join-------->|                              |
      |        @MessageMapping runs, stores "Alice" in session      |
      |        @SendTo publishes JOIN to broker                     |
      |<-MESSAGE /topic/chat---------|--MESSAGE /topic/chat-------->|
      |  "▶ Alice joined"            |  "▶ Alice joined"            |
      |--SEND /app/chat.send-------->|                              |
      |        @MessageMapping runs, returns message                |
      |        broker broadcasts                                    |
      |<-MESSAGE /topic/chat---------|--MESSAGE /topic/chat-------->|
      |  "Alice: hello"              |  "Alice: hello"              |
      |--STOMP DISCONNECT----------->|                              |
      |        SessionDisconnectEvent fires                         |
      |        messagingTemplate sends LEAVE to broker              |
      |                              |--MESSAGE /topic/chat-------->|
      |                              |  "◀ Alice left"              |
```

### Run it
```bash
cd websocket-chat-spring
mvn spring-boot:run
# Open http://localhost:8080 in multiple browser tabs
```

---

## Security

### WSS — WebSocket Secure

`ws://` sends data as plain text over the network. `wss://` runs WebSocket over TLS (same as HTTPS). Always use `wss://` in production — everything else is the same, TLS is handled at the transport layer before WebSocket sees it.

### Authentication — the key problem

HTTP has a request/response cycle per call, so you can attach an `Authorization` header to every request. WebSocket has one handshake and then a persistent connection. The question is: when and how do you prove who you are?

**Option 1: Authenticate at the handshake (recommended)**
Send a JWT as a query parameter or custom header during the initial HTTP upgrade request. The server validates it before allowing the connection to upgrade.

```
GET /ws-chat?token=eyJhbGci... HTTP/1.1
Upgrade: websocket
```

In Spring, intercept this in a `ChannelInterceptor`:

```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
        String token = accessor.getFirstNativeHeader("Authorization");
        // validate token, set user principal
    }
    return message;
}
```

**Option 2: First message after connect carries credentials**
Client connects, then immediately sends an auth message. Server holds the connection in an unauthenticated state until credentials arrive. More complex, easy to get wrong.

**Option 3: Rely on HTTP session cookie**
If the user already has an authenticated HTTP session (e.g. logged in via `/login`), the browser sends the session cookie automatically on the WebSocket upgrade request. Spring Security can read it. Simple but ties WebSocket auth to HTTP session lifecycle.

### Origin Header Checking

Browsers always send an `Origin` header on WebSocket connections. A malicious website at `evil.com` could try to open a WebSocket to `yourapp.com` using the victim's cookies. The server should reject connections from unexpected origins:

```java
registry.addEndpoint("/ws-chat")
    .setAllowedOrigins("https://yourapp.com") // reject all other origins
    .withSockJS();
```

### CSRF

WebSocket connections initiated from a browser carry cookies automatically — the same vulnerability as HTTP CSRF. Mitigate by:
1. Checking the `Origin` header (above)
2. Requiring a CSRF token in the STOMP CONNECT headers

---

## Heartbeat and Dead Connection Detection

### Why connections die silently

A WebSocket connection can go dead without either side sending a close frame — the user's laptop closes the lid, a mobile network switches towers, a corporate proxy drops idle connections after 60 seconds. Neither the client nor server gets notified. The server keeps the session object in memory; the client thinks it's still connected.

### WebSocket ping/pong frames

The WebSocket spec has built-in ping and pong control frames. The server sends a PING; the client must reply with a PONG. If no PONG arrives within a timeout, the server closes the session.

In Tyrus (Stage 2 & 3), configure it on the server:
```java
server.getServerProperties().put("org.glassfish.tyrus.incomingBufferSize", 4194304);
// Tyrus sends ping every 30s by default
```

### STOMP heartbeat (Stage 4)

STOMP has its own heartbeat negotiated in the CONNECT/CONNECTED frames:
```
heart-beat:4000,4000   // "I can send every 4s, I expect every 4s"
```
Spring's `SimpleBrokerMessageHandler` uses this to detect dead sessions and clean them up automatically.

### Proxy timeout problem

Many load balancers and proxies (AWS ALB default: 60s, Nginx default: 75s) close connections that carry no data. Heartbeats solve this — a ping/pong every 25–30 seconds keeps the connection alive through most proxies.

---

## WebSocket vs SSE vs Long Polling — When to Use Which

| | WebSocket | SSE | Long Polling |
|---|---|---|---|
| Direction | Full-duplex (both ways) | Server → client only | Server → client only |
| Protocol | WebSocket (upgrade from HTTP) | Plain HTTP | Plain HTTP |
| Browser reconnect | Manual (you write it) | Automatic | Manual |
| Overhead | Low (after handshake) | Low | High (new request each time) |
| Load balancer friendly | Needs sticky sessions or broker | Yes (stateless HTTP) | Yes |
| Binary support | Yes | No (text only) | No |

**Use WebSocket when:** client also needs to send frequent messages to server (chat, multiplayer games, collaborative editing, trading terminals).

**Use SSE when:** server pushes updates, client rarely or never sends back (live dashboards, news feeds, progress bars, notifications). SSE is simpler — it's just HTTP, works through any proxy, browser reconnects automatically.

**Use Long Polling when:** you need broad compatibility and updates are infrequent. Last resort — higher server resource cost.

**Common mistake:** using WebSocket for something that only needs one-way push. SSE is simpler, scales better (stateless HTTP), and requires zero special infrastructure.

---

## Load Balancing WebSockets

### The sticky session problem

A standard round-robin load balancer routes each new request to a different server. For HTTP this is fine — each request is independent. For WebSocket it's a problem:

```
Alice connects → Server 1 (her session lives here)
Alice's next message → round-robin sends to Server 2 → Server 2 has no session for Alice → error
```

**Solution 1: Sticky sessions (session affinity)**
The load balancer always routes a given client to the same server — typically by hashing the client IP or a session cookie. Simple but has failure mode: if Server 1 dies, all its sessions are lost and clients must reconnect.

```nginx
upstream websocket_servers {
    ip_hash;  # same client IP always goes to same server
    server server1:8080;
    server server2:8080;
}
```

**Solution 2: External message broker (the right answer at scale)**
Clients can connect to any server. Servers don't communicate directly — they all publish and subscribe through a shared broker (RabbitMQ, Redis Pub/Sub). A message from Alice on Server 1 reaches Carol on Server 2 via the broker. No sticky sessions needed, any server can fail without affecting other servers' clients.

```
Alice (Server 1) ──publish──► RabbitMQ ──subscribe──► Server 2 ──► Carol
```

---

## Performance and Connection Limits

### Connections are cheap with non-blocking I/O

The old "thread-per-connection" model (one OS thread per WebSocket) limits you to ~10,000 connections before memory is exhausted (the C10K problem — each thread uses ~1MB stack). Modern servers use **non-blocking I/O**:

- **Netty, Undertow** — event loop model. A small fixed pool of threads handles thousands of connections by never blocking. One thread can manage 10,000+ idle WebSocket connections.
- **Spring Boot default** (Tomcat) — uses NIO connectors, handles WebSocket connections non-blockingly.

### Memory per connection

A WebSocket connection at rest uses roughly:
- ~4KB for the OS socket buffer
- Your session object (headers, attributes you store)
- Any pending messages in send queues

A single commodity server can realistically hold 50,000–100,000 idle WebSocket connections. The bottleneck is usually CPU (message processing) before memory.

### What actually limits you

1. **File descriptor limit** — each connection is an OS file descriptor. Default limit is often 1024 per process. Raise it: `ulimit -n 100000` or in `/etc/security/limits.conf`.
2. **Port range** — on the client side each connection uses a local port (65535 max). On server side this doesn't apply — all clients share port 8080.
3. **Message throughput** — idle connections are cheap; processing 100k messages/second is what taxes CPU.

---

## Reconnection Strategy

Network blips, server restarts, and proxy timeouts will drop connections. Clients must reconnect — but not all at once.

### Exponential backoff with jitter

If 10,000 clients all reconnect simultaneously after a server restart, you create a thundering herd that immediately overwhelms the server.

```javascript
let retryDelay = 1000; // start at 1 second

function connect() {
    socket = new WebSocket('wss://yourapp.com/ws');

    socket.onclose = function() {
        const jitter = Math.random() * 1000;          // add randomness
        setTimeout(connect, retryDelay + jitter);
        retryDelay = Math.min(retryDelay * 2, 30000); // double each time, cap at 30s
    };

    socket.onopen = function() {
        retryDelay = 1000; // reset on successful connect
    };
}
```

The jitter spreads reconnections across time so they don't all hit the server at the same instant.

### What to do with missed messages

When a client reconnects, it has missed messages sent while it was disconnected. Common approaches:
1. **Ignore** — acceptable for ephemeral data (live cursor positions, sensor readings)
2. **Replay from last seen ID** — client sends its last received message ID on reconnect; server replays from there (requires message persistence)
3. **Full state sync on reconnect** — server sends current state snapshot; client discards incremental updates it missed

---

## Observability

Key metrics to monitor in a WebSocket system:

| Metric | Why it matters |
|---|---|
| Active connection count | Capacity planning; sudden drop = mass disconnect |
| Connection rate (connects/sec) | Spike = reconnect storm |
| Message throughput (msg/sec) | Processing capacity |
| Message latency (p50, p99) | Real-time SLA health |
| Errors / close codes | Code 1006 (abnormal closure) = network issues |
| Broker queue depth | If using external broker — backpressure indicator |

WebSocket close codes to know:
- `1000` — normal closure
- `1001` — endpoint going away (server restart)
- `1006` — abnormal closure, no close frame received (network drop, proxy timeout)
- `1008` — policy violation (auth failure)
- `1011` — server error

---

## Common Interview Questions

**Q: What's the difference between WebSocket and HTTP?**
HTTP is request-response and client-initiated; connection closes after each exchange. WebSocket is a persistent, full-duplex TCP connection where either side can send at any time. WebSocket starts as an HTTP request and upgrades via `101 Switching Protocols`.

**Q: When would you choose SSE over WebSocket?**
When you only need server-to-client push (notifications, dashboards, live feeds). SSE is plain HTTP — stateless, proxy-friendly, browser reconnects automatically, scales without sticky sessions. WebSocket is overkill if the client never sends data back.

**Q: How do you authenticate a WebSocket connection?**
Authenticate at handshake time — validate a JWT in the query parameter or STOMP CONNECT headers before allowing the upgrade. Don't trust the connection is authenticated just because the TCP connection is open.

**Q: How do you scale WebSockets horizontally?**
Replace the in-memory session store with an external message broker (RabbitMQ, Redis Pub/Sub). All server instances publish/subscribe through the broker. A message published on Server 1 reaches clients connected to Server 2. No sticky sessions needed.

**Q: What happens if a client disconnects without sending a close frame?**
The server-side session stays open and accumulates in memory until a heartbeat timeout fires. This is why heartbeats (WebSocket ping/pong or STOMP heart-beat) are essential — they detect dead connections within seconds and clean up server resources.

**Q: What is STOMP and why use it over raw WebSocket?**
STOMP is a messaging subprotocol over WebSocket. It adds destinations (`/topic/chat`), subscriptions, and structured message routing. Without it you have to invent your own routing, serialisation, and pub/sub conventions. Spring Boot's STOMP support also makes swapping from an in-memory broker to a production broker (RabbitMQ) a config-only change.

**Q: How would you handle a reconnection storm after a server restart?**
Exponential backoff with jitter on the client side. Each client waits a random amount before reconnecting, and doubles the wait on each failed attempt up to a cap. This spreads the load over time instead of all 50,000 clients hitting the server in the same second.

**Q: What's the C10K problem and how does it relate to WebSockets?**
C10K = handling 10,000 concurrent connections on one server. Thread-per-connection models fail here (each thread ~1MB stack → 10GB RAM for 10k threads). Modern servers use non-blocking I/O (Netty, Undertow) where a small thread pool handles all connections via an event loop. One thread services thousands of connections without blocking.
