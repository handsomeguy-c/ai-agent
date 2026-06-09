import { BrowserRouter } from "react-router-dom";
import MindPilotLayout from "./components/MindPilotLayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";

function App() {
  return (
    <BrowserRouter>
      <ChatSessionsProvider>
        <MindPilotLayout />
      </ChatSessionsProvider>
    </BrowserRouter>
  );
}

export default App;
