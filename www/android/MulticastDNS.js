module.exports = {
    query: function (host, successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, "MulticastDNSPlugin", "query", [host]);
    }
};