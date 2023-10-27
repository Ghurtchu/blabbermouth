# Live Support Chat Server

Service oriented architecture with two backends:
- `ChatServer`:
  - publishes user join request messages to `Join` channel in `Redis pub/sub`
  - enables `User` and `Support` to exchange messages via chat using `WebSockets`
- `Subscriber`:
  - subscribes to `Join` channel in `Redis pub/sub` and forwards & filters messages to UI using `WebSockets`

**Protocol and flow description for building UI**:

1) Register `User` in `ChatServer` by sending HTTP request to: `GET localhost:9000/user/join/{userName}`
   - Expect `JSON` response:
    ```json
    {
        "userId": "9d6d2db0cdb84d8ba67105670d94d641",
        "username": "Nika",
        "chatId": "d2464b5386044daf9e36ffd414260f67"
    }
    ```
2) Initiate WebSocket connection for `User` in `ChatServer` by sending request to: `GET localhost:9000/chat/{chatId}`
   - attach request body:
    ```json
    {
        "type": "Join",
        "args": {
            "userId": "8b5ee49aa3bb45e1a8719179e5e25c12",
            "username": "Nika",
            "from": "User"
        }
    }
    ```
   - Expect `JSON` response:
    ```json
    {
       "type": "Joined",
       "args": {
           "participant": "User",
           "userId": "f49f3cc665af4cb38092af714a4c87fa"
        }
    }
    ```
3) Initiate WebSocket connection for `Support` in `Subscriber` by sending request to: `GET localhost:9001/joins`
   - Expect `JSON` WebSocket messages:
     ```json
     {
         "participant": "User",
         "userId": "...",
         "chatId": "..."
     }
     ```
   - once `Support` clicks to the special user request UI must send the `userId` as websocket message so that it gets filtered out for other `Support` agents
4) Initiate WebSocket connection for `Support` in `ChatServer` by sending request to: `GET localhost:9000/chat/{chatId}`
   - attach request body:
    ```json
    {
        "type": "Join",
        "args": {
            "userId" : "f49f3cc665af4cb38092af714a4c87fa",
            "supportUserName": "Vika",
            "username": "Nika",
            "from": "Support"    
        }
    }
   ```
  - Expect `JSON` response:
    ```json
    {
        "type": "Joined",
        "args": {
            "participant": "Support",
            "userId": "f49f3cc665af4cb38092af714a4c87fa",
            "supportId": "7c270542e64a4dfbb2bc1c7793746674",
            "supportUserName": "Vika"
          }
    }
    ```
5) Send chat message to `Support` from `User` by sending the following WebSocket text message on the opened WS connection:
    ```json
    {
        "type": "ChatMessage",
        "args": {
            "userId" : "f49f3cc665af4cb38092af714a4c87fa",
            "supportId": "7c270542e64a4dfbb2bc1c7793746674",
            "content": "Hey, I want to tranasfer money offshore, how can I do it via internet bank?",
            "from": "User"    
        }
    }
    ```
   - Expect `JSON` response for both `User` and `Support` WebSocket connections:
   ```json
    {
        "type": "ChatMessage",
        "args": {
            "userId": "f49f3cc665af4cb38092af714a4c87fa",
            "supportId": "7c270542e64a4dfbb2bc1c7793746674",
            "content": "hey",
            "timestamp": "2023-10-22T18:18:59.360855Z",
            "from": "User"
        }
    }
   ```
6) Send chat message to `User` from `Support` by sending the following WebSocket text message on the opened WS connection:  
    ```json
    {
        "type": "ChatMessage",
        "args": {
            "userId" : "f49f3cc665af4cb38092af714a4c87fa",
            "supportId": "7c270542e64a4dfbb2bc1c7793746674",
            "supportUserName": "Vika",
            "content": "Hello, please navigate to Transfer and then select Offshore :)",
            "from": "Support"    
        }
    }
    ```
    - Expect `JSON` response for both `User` and `Support` WebSocket connections:
   ```json
    {
        "type": "ChatMessage",
        "args": {
            "userId": "f49f3cc665af4cb38092af714a4c87fa",
            "supportId": "7c270542e64a4dfbb2bc1c7793746674",
            "content": "Hello, please navigate to Transfer and then select Offshore :)",
            "timestamp": "2023-10-22T18:19:19.119515Z",
            "from": "Support"
        }
   }
   ```
   
Each chat expires after two minutes of inactivity. Also, WebSocket connection automatically closes after 120 seconds of inactivity.

In case `User` or `Support` refreshes browser backend either loads:
- conversation history (if the chat is still active)
- message about chat expiration (if the chat was expired)

7) In case `User` refreshes browser the UI must send new `Join` (re-join) WebSocket text message:
    ```json
    {   
        "type": "Join",
        "args": {
            "userId": "8b5ee49aa3bb45e1a8719179e5e25c12",
            "supportId": "7c270542e64a4dfbb2bc1c7793746674",
            "username": "Nika",
            "from": "User"
        }
   }
   ```
   - Expect `JSON` response:
    ```json
    {
        "type": "ChatHistory",
        "args": {
            "messages": [
                {
                    "userId": "718b568c22f6460ca46a07cfc6aae3ba",
                    "supportId": "35eda9304e554514915fdbb8f26b710d",
                    "content": "Hey, I want to tranasfer money offshore, how can I do it via internet bank?",
                    "timestamp": "2023-10-22T18:36:54.834408Z",
                    "from": "User"
                },
                {
                    "userId": "718b568c22f6460ca46a07cfc6aae3ba",
                    "supportId": "35eda9304e554514915fdbb8f26b710d",
                    "content": "Hello, please navigate to Transfer and then select Offshore :)",
                    "timestamp": "2023-10-22T18:37:10.219775Z",
                    "from": "Support"
                }
            ]
        }
    }
    ```
   
8) Same message is loaded for `Support`, however a bit different `Join` (re-join) WebSocket text message must be sent:
    ```json
    {   
        "type": "Join",
        "args": {
            "userId": "8b5ee49aa3bb45e1a8719179e5e25c12",
            "supportId": "7c270542e64a4dfbb2bc1c7793746674",
            "username": "Nika",
            "supportUserName": "Vika",
            "from": "Support"
        }
   }
   ```





