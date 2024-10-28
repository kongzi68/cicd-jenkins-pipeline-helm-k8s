def call(url, auth, reqBody, httpMode='POST') {
    response = httpRequest (
        url: url,
        authentication: auth,
        contentType: 'APPLICATION_FORM',
        quiet: true,
        requestBody: reqBody,
        httpMode: httpMode,
        ignoreSslErrors: true
    )
    if (response.status == 200) {
        echo "Response: ${response}"
    } else {
        echo "Request failed with status code: ${response.status}"
        echo "Response content: ${response.content}"
    }
    if (httpMode == 'DELETE') {
        return response.status
    } else {
        return response.content
    } 
}
