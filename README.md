# Live Support Chat Server

**Protocol and flow description for building UI**:

1) Register `User` in server by sending HTTP request to: `GET localhost:9000/user/join/{userName}`
   - Expect `JSON` response:
    ```json
    {
        "userId": "9d6d2db0cdb84d8ba67105670d94d641",
        "username": "Nika",
        "chatId": "d2464b5386044daf9e36ffd414260f67"
    }
    ```
2) Initiate WebSocket connection for `User` by sending request to: `GET localhost:9000/chat/{chatId}`
   - attach request body:

    ```json
    {
        "type": "Join",
        "args": {
            "userId": "8b5ee49aa3bb45e1a8719179e5e25c12",
            "chatId": "0bbf78fa742542a5b617e60483ca1e93",
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
           "userId": "f49f3cc665af4cb38092af714a4c87fa",
           "chatId": "009cf42388504040a297df6a11e9c801"
        }
    }
    ```
3) Initiate WebSocket connection for `Support` by sending request to: `GET localhost:9000/chat/{chatId}`
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
            "chatId": "009cf42388504040a297df6a11e9c801",
            "supportId": "7c270542e64a4dfbb2bc1c7793746674",
            "supportUserName": "Vika"
          }
    }
    ```
4) Send chat message to `Support` from `User` by sending the following WebSocket text message on the opened WS connection:
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
5) Send chat message to `User` from `Support` by sending the following WebSocket text message on the opened WS connection:  
    ```json
    {
        "type": "ChatMessage",
        "args": {
            "userId" : "f49f3cc665af4cb38092af714a4c87fa",
            "supportId": "7c270542e64a4dfbb2bc1c7793746674",
            "supportUserName": "Vika",
            "content": "hi?",
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
             "content": "hi?",
            "timestamp": "2023-10-22T18:19:19.119515Z",
            "from": "Support"
        }
   }
   ```
   
Each chat expires as soon as server knows that its last message has been written more than two minutes ago.

6) In case either `User` or `Support` refreshes browser backend either loads:
- conversation history:
- message about chat expiration





