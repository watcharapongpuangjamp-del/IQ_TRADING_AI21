import os
import json
import urllib.request
import urllib.parse
from typing import Dict, Any, Optional

class IQAirClient:
    """
    Client for IQAir AirVisual API (v2) providing air quality index (AQI),
    pollution metrics, and macro environmental indicators using standard library.
    """
    BASE_URL = "https://api.airvisual.com/v2"

    def __init__(self, api_key: Optional[str] = None):
        self.api_key = api_key or os.getenv("IQAIR_API_KEY", "")

    async def get_city_air_quality(self, city: str = "London", state: str = "London", country: str = "United Kingdom") -> Dict[str, Any]:
        """
        Fetch current air quality and pollution data for a specified city.
        If no API key is set or request fails, returns realistic mock environmental macro data.
        """
        if not self.api_key or self.api_key.strip() == "":
            return self._get_fallback_data(city, country, "Mock fallback (API key not configured)")

        params = {
            "city": city,
            "state": state,
            "country": country,
            "key": self.api_key
        }
        query_string = urllib.parse.urlencode(params)
        url = f"{self.BASE_URL}/city?{query_string}"

        try:
            req = urllib.request.Request(url, headers={"User-Agent": "IQTradingTerminal/9.0"})
            with urllib.request.urlopen(req, timeout=5.0) as response:
                if response.status == 200:
                    raw_data = response.read().decode("utf-8")
                    data = json.loads(raw_data)
                    if data.get("status") == "success":
                        result_data = data.get("data", {})
                        current = result_data.get("current", {})
                        pollution = current.get("pollution", {})
                        weather = current.get("weather", {})
                        return {
                            "success": True,
                            "city": result_data.get("city", city),
                            "state": result_data.get("state", state),
                            "country": result_data.get("country", country),
                            "aqi_us": pollution.get("aqius", 42),
                            "main_pollution": pollution.get("mainus", "p2"),
                            "temperature_c": weather.get("tp", 20),
                            "humidity_pct": weather.get("hu", 55),
                            "wind_speed_ms": weather.get("ws", 3.2),
                            "pressure_hpa": weather.get("pr", 1013),
                            "macro_impact_score": self._calculate_macro_impact(pollution.get("aqius", 42)),
                            "source": "iqair_api_v2",
                            "detail": "Live IQAir data fetched successfully."
                        }
        except Exception:
            pass

        return self._get_fallback_data(city, country, "Network error or invalid API key, fallback used.")

    def _get_fallback_data(self, city: str, country: str, detail: str) -> Dict[str, Any]:
        return {
            "success": True,
            "city": city,
            "state": "Financial Hub",
            "country": country,
            "aqi_us": 38,
            "main_pollution": "p2",
            "temperature_c": 19.5,
            "humidity_pct": 60,
            "wind_speed_ms": 4.1,
            "pressure_hpa": 1015,
            "macro_impact_score": 0.88,
            "source": "iqair_fallback_simulation",
            "detail": detail
        }

    def _calculate_macro_impact(self, aqi: int) -> float:
        if aqi <= 50:
            return 0.95
        elif aqi <= 100:
            return 0.85
        elif aqi <= 150:
            return 0.70
        else:
            return 0.55
