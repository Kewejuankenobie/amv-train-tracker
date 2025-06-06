import TrainInfo from "./pages/TrainInfo.tsx";
import {APIProvider} from "@vis.gl/react-google-maps";
import 'dotenv'
import {BrowserRouter, Route, Routes} from "react-router-dom";
import Navbar from "./components/Navbar.tsx";
import StationInfo from "./pages/StationInfo.tsx";
import Home from "./pages/Home.tsx";

function App() {

    //API key for the Google Maps API
    const key: string = import.meta.env.VITE_API_KEY;


  return (
    <>
      <div>
          <APIProvider apiKey={key} onLoad={() => console.log("Maps API Loaded")}>
              <BrowserRouter>
                  <Navbar />
                  <Routes>
                      <Route path="train-info" element={<TrainInfo />} />
                      <Route path="station-info" element={<StationInfo />} />
                      <Route index element={<Home />} />
                  </Routes>
              </BrowserRouter>
          </APIProvider>
      </div>
    </>
  )
}

export default App
