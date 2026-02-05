@import java.util.List
@param String currentModel
@param List<String> supportedModels

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Configuration</title>
</head>
<body>
    <h1>Gemini Configuration</h1>
    <form action="/config/model" method="POST">
        <label for="modelName">Select Model:</label>
        <select name="modelName" id="modelName">
            @for(String model : supportedModels)
                <option value="${model}" selected="${model.equals(currentModel)}">${model}</option>
            @endfor
        </select>
        <button type="submit">Save</button>
    </form>
    <p><a href="/">Back to Home</a></p>
</body>
</html>
